package com.example.mgauss

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.collections.toDoubleArray
import kotlin.math.sqrt

data class InferenceResult(
    val label: String,
    val confidence: Float,
    val sigma: Float
)

class InferenceEngine(context: Context) {

    private var tflite: Interpreter? = null
    private val WINDOW_SIZE = 100
    private val START_OFFSET = 200.0
    private val TARGET_INTERVAL = 10.0
    private val b = doubleArrayOf(0.43284664499029174, -1.731386579961167, 2.5970798699417506, -1.731386579961167, 0.43284664499029174)
    private val a = doubleArrayOf(1.0, -2.3695130071820376, 2.31398841441588, -1.0546654058785674, 0.18737949236818488)

    init {
        var options: Interpreter.Options
        try {
            options = Interpreter.Options()
            tflite = Interpreter(loadModelFile(context), options)
        } catch (e: Exception) {
        }
    }

    fun predict(fullBuffer: List<SensorSnapshot>, currentTimeNs: Long): InferenceResult {
        var targetDurationNs: Long
        var windowStartTime: Long
        var safeMargin: Long
        var relevantData: List<SensorSnapshot>
        var t0: Double
        var relTime: DoubleArray
        var earthMx: DoubleArray
        var earthMy: DoubleArray
        var earthMz: DoubleArray
        var rawMx: DoubleArray
        var rawMy: DoubleArray
        var rawMz: DoubleArray
        var filtMx: DoubleArray
        var filtMy: DoubleArray
        var filtMz: DoubleArray
        var q: SensorSnapshot
        var rotated: Triple<Double, Double, Double>
        var ex: Double
        var ey: Double
        var ez: Double
        var interpMx: DoubleArray
        var interpMy: DoubleArray
        var interpMz: DoubleArray
        var sigma: Double
        var zScoreResult: Triple<DoubleArray, DoubleArray, DoubleArray>
        var normMx: DoubleArray
        var normMy: DoubleArray
        var normMz: DoubleArray
        var inputWave: Array<Array<FloatArray>>
        var inputSigma: Array<FloatArray>
        var outputBuffer: Array<FloatArray>
        var inputs: Array<Any>
        var outputs: Map<Int, Any>
        var probabilities: FloatArray
        var probNeutral: Float
        var probDevice: Float
        var label: String
        var confidence: Float

        targetDurationNs = 1_000_000_000L
        windowStartTime = currentTimeNs - targetDurationNs
        safeMargin = 200_000_000L
        relevantData = fullBuffer.filter { it.timestamp >= (windowStartTime - safeMargin) }

        if (relevantData.isEmpty()) return InferenceResult("Buffering", 0f, 0f)

        if (relevantData.first().timestamp > windowStartTime) {
            return InferenceResult("Buffering (Gap)", 0f, 0f)
        }

        t0 = windowStartTime.toDouble()

        relTime = relevantData.map { (it.timestamp.toDouble() - t0) / 1_000_000.0 }.toDoubleArray()
        earthMx = DoubleArray(relevantData.size)
        earthMy = DoubleArray(relevantData.size)
        earthMz = DoubleArray(relevantData.size)

        rawMx = relevantData.map { it.mx }.toDoubleArray()
        rawMy = relevantData.map { it.my }.toDoubleArray()
        rawMz = relevantData.map { it.mz }.toDoubleArray()

        filtMx = applyFiltFilt(rawMx)
        filtMy = applyFiltFilt(rawMy)
        filtMz = applyFiltFilt(rawMz)

        for (i in relevantData.indices) {
            q = relevantData[i]
            rotated = rotateToEarth(filtMx[i], filtMy[i], filtMz[i], q.qx, q.qy, q.qz, q.qw)
            ex = rotated.first
            ey = rotated.second
            ez = rotated.third
            earthMx[i] = ex; earthMy[i] = ey; earthMz[i] = ez
        }

        interpMx = interpolateToGrid(relTime, earthMx, 100, 0.0)
        interpMy = interpolateToGrid(relTime, earthMy, 100, 0.0)
        interpMz = interpolateToGrid(relTime, earthMz, 100, 0.0)

        sigma = calculateSigma(interpMx, interpMy, interpMz)
        zScoreResult = applyGlobalZScore(interpMx, interpMy, interpMz)
        normMx = zScoreResult.first
        normMy = zScoreResult.second
        normMz = zScoreResult.third

        inputWave = Array(1) { Array(WINDOW_SIZE) { FloatArray(3) } }
        for (i in 0 until WINDOW_SIZE) {
            inputWave[0][i][0] = normMx[i].toFloat()
            inputWave[0][i][1] = normMy[i].toFloat()
            inputWave[0][i][2] = normMz[i].toFloat()
        }

        inputSigma = Array(1) { FloatArray(1) { sigma.toFloat() } }
        outputBuffer = Array(1) { FloatArray(2) }

        inputs = arrayOf(inputWave, inputSigma)
        outputs = mapOf(0 to outputBuffer)

        try {
            tflite?.runForMultipleInputsOutputs(inputs, outputs)
            probabilities = outputBuffer[0]
            probNeutral = probabilities[0]
            probDevice = probabilities[1]

            label = if (probDevice > probNeutral) "Device" else "Neutral"
            confidence = if (probDevice > probNeutral) probDevice else probNeutral

            return InferenceResult(label, confidence, sigma.toFloat())

        } catch (e: Exception) {
            return InferenceResult("Error", 0f, 0f)
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        var fileDescriptor = context.assets.openFd("Magdroid_model.tflite")
        var inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        var fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun interpolateToGrid(time: DoubleArray, data: DoubleArray, count: Int, startOffset: Double): DoubleArray {
        var result: DoubleArray
        var tTarget: Double
        var k: Int
        var t1: Double
        var t2: Double
        var v1: Double
        var v2: Double

        result = DoubleArray(count)
        for (i in 0 until count) {
            tTarget = startOffset + (i * TARGET_INTERVAL)
            k = -1
            for (j in time.indices) {
                if (time[j] > tTarget) {
                    k = j
                    break
                }
            }
            if (k > 0) {
                t1 = time[k - 1]; t2 = time[k]
                v1 = data[k - 1]; v2 = data[k]
                if (t2 - t1 > 0) {
                    result[i] = v1 + (v2 - v1) * ((tTarget - t1) / (t2 - t1))
                } else result[i] = v1
            } else if (k == 0) result[i] = data.firstOrNull() ?: 0.0
            else result[i] = data.lastOrNull() ?: 0.0
        }
        return result
    }

    private fun rotateToEarth(mx: Double, my: Double, mz: Double, qx: Double, qy: Double, qz: Double, qw: Double): Triple<Double, Double, Double> {
        var q_x: Double
        var q_y: Double
        var q_z: Double
        var q_w: Double
        var vx: Double
        var vy: Double
        var vz: Double
        var c1x: Double
        var c1y: Double
        var c1z: Double
        var t1x: Double
        var t1y: Double
        var t1z: Double
        var c2x: Double
        var c2y: Double
        var c2z: Double

        q_x = -qx; q_y = -qy; q_z = -qz; q_w = qw
        vx = mx; vy = my; vz = mz

        c1x = q_y * vz - q_z * vy
        c1y = q_z * vx - q_x * vz
        c1z = q_x * vy - q_y * vx
        t1x = c1x + q_w * vx
        t1y = c1y + q_w * vy
        t1z = c1z + q_w * vz
        c2x = q_y * t1z - q_z * t1y
        c2y = q_z * t1x - q_x * t1z
        c2z = q_x * t1y - q_y * t1x

        return Triple(vx + 2.0 * c2x, vy + 2.0 * c2y, vz + 2.0 * c2z)
    }

    private fun applyFiltFilt(data: DoubleArray): DoubleArray {
        var forward: DoubleArray
        var backward: DoubleArray
        forward = lfilter(data)
        backward = lfilter(forward.reversedArray())
        return backward.reversedArray()
    }

    private fun lfilter(x: DoubleArray): DoubleArray {
        var y: DoubleArray
        y = DoubleArray(x.size)
        for (n in x.indices) {
            for (i in b.indices) if (n - i >= 0) y[n] += b[i] * x[n - i]
            for (j in 1 until a.size) if (n - j >= 0) y[n] -= a[j] * y[n - j]
            y[n] /= a[0]
        }
        return y
    }

    private fun calculateSigma(mx: DoubleArray, my: DoubleArray, mz: DoubleArray): Double {
        return (getStd(mx) + getStd(my) + getStd(mz)) / 3.0
    }

    private fun getStd(data: DoubleArray): Double {
        var mean: Double
        var sumSq: Double
        var diff: Double

        if (data.isEmpty()) return 0.0
        mean = data.average()
        sumSq = 0.0
        for (v in data) {
            diff = v - mean
            sumSq += diff * diff
        }
        return sqrt(sumSq / data.size)
    }

    private fun applyGlobalZScore(mx: DoubleArray, my: DoubleArray, mz: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        var sum: Double
        var count: Int
        var mean: Double
        var sumSqDiff: Double
        var std: Double
        var divisor: Double
        var nmx: DoubleArray
        var nmy: DoubleArray
        var nmz: DoubleArray

        sum = 0.0
        count = 0

        for (v in mx) { sum += v; count++ }
        for (v in my) { sum += v; count++ }
        for (v in mz) { sum += v; count++ }

        mean = if (count > 0) sum / count else 0.0

        sumSqDiff = 0.0
        for (v in mx) { val d = v - mean; sumSqDiff += d * d }
        for (v in my) { val d = v - mean; sumSqDiff += d * d }
        for (v in mz) { val d = v - mean; sumSqDiff += d * d }

        std = if (count > 0) sqrt(sumSqDiff / count) else 0.0
        divisor = if (std == 0.0) 1.0 else std

        nmx = DoubleArray(mx.size) { (mx[it] - mean) / divisor }
        nmy = DoubleArray(my.size) { (my[it] - mean) / divisor }
        nmz = DoubleArray(mz.size) { (mz[it] - mean) / divisor }

        return Triple(nmx, nmy, nmz)
    }
}