package com.morselight_flashlightwithmorsecode.app.fragments

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.morselight_flashlightwithmorsecode.app.MainViewModel
import com.morselight_flashlightwithmorsecode.app.R
import com.morselight_flashlightwithmorsecode.app.utils.*
import kotlinx.android.synthetic.main.fragment_auto_decode.*
import kotlinx.android.synthetic.main.fragment_auto_decode.decoded_message
import kotlinx.android.synthetic.main.fragment_auto_decode.flash_status_view
import kotlinx.android.synthetic.main.fragment_auto_decode.incoming_message
import kotlinx.android.synthetic.main.fragment_auto_decode.report_button
import kotlinx.android.synthetic.main.fragment_auto_decode.reset_button
import kotlinx.android.synthetic.main.fragment_auto_decode.signal_button
import kotlinx.android.synthetic.main.fragment_auto_decode.sos_button
import kotlinx.android.synthetic.main.fragment_manual_decode.*
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*


typealias LumaListener = (luma: Double) -> Unit

@KoinApiExtension
class AutoDecodeFragment : Fragment(R.layout.fragment_auto_decode), KoinComponent,
    ImageAnalysisListener {

    private val sharedPref: SharedPreferenceUtils by inject()
    private var isFlashOn = false
    private var ignoreClicks = false
    private var transmissionSpeed: Int = 3
    private var callback: FragmentCallbacks? = null
    private var startCapturing = false
    private var stopCapturingLowLuminosity = false
    private var avgLowLuminosity = 0.0
    private var avgHighLuminosity = 0.0
    private var avgCounter = 0
    private var perceptibility = 30
    private var percentageRectSize = 50
    private val handler = Handler(Looper.getMainLooper())
    private val viewModel: MainViewModel by activityViewModels()
    private val timings = arrayListOf<Long>()
    private val diffTimings = arrayListOf<Long>()

    companion object {
        private const val TAG = "AutoDecode"
        private const val SPEED = "speed"
        private const val REACT_SIZE = "react_size"
        private const val PERCEPTIBILITY = "perceptibility"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        transmissionSpeed = sharedPref.getInt(SPEED, 3)
        percentageRectSize = sharedPref.getInt(REACT_SIZE, 50)
        perceptibility = sharedPref.getInt(PERCEPTIBILITY, 30)

        size_slider.value = percentageRectSize / 100f
        callback?.updateRectAreaPerc(percentageRectSize)

        perceptibility_slider.value = perceptibility.toFloat()
        setRectConstraints(percentageRectSize / 100f)

        start_stop_button.setOnClickListener {
            if (ignoreClicks) {
                runCleanUp()
                callback?.removeHandlers()
            } else {
                if (!startCapturing) {
                    // Start capturing high luminosity to on flash timings and lows to off flash timings
                    callback?.acquireWakeLock()
                    startCapturing = true
                    report_button.gone()
                    incoming_message.text = ""
                    decoded_message.text = ""
                    start_stop_button.text = getString(R.string.stop)
                    sos_button.isEnabled = false
                    signal_button.isEnabled = false
                    handler.postDelayed(timer3Sec, 0)
                    handler.postDelayed(timer2Sec, 1000)
                    handler.postDelayed(timer1Sec, 2000)
                    handler.postDelayed(timer0Sec, 2950)
                } else {
                    callback?.releaseWakeLock()
                    startCapturing = false
                    stopCapturingLowLuminosity = false
                    sos_button.isEnabled = true
                    signal_button.isEnabled = true
                    start_stop_button.text = getString(R.string.start)
                    start_timer.text = ""
                    isFlashOn = false
                    flash_status_view.gone()
                    avgLowLuminosity = 0.0
                    avgHighLuminosity = 0.0
                    avgCounter = 0
                    decodeNotedTimings()
                    removeHandlerCallbacks()
                }
            }
        }

        perceptibility_title.isSelected = true
        rect_size_title.isSelected = true
        incoming_message.movementMethod = ScrollingMovementMethod()

        perceptibility_slider.addOnChangeListener { _, value, _ ->
            perceptibility = value.toInt()
            sharedPref.setInt(PERCEPTIBILITY, perceptibility)
        }

        size_slider.addOnChangeListener { _, value, _ ->
            percentageRectSize = (value * 100).toInt()
            sharedPref.setInt(REACT_SIZE, percentageRectSize)
            setRectConstraints(value)
            callback?.updateRectAreaPerc(percentageRectSize)
        }

        signal_button.setOnClickListener {
            if (ignoreClicks) return@setOnClickListener
            val charMessage = arrayListOf('E', 'E', 'E')
            playWithFlash(charMessage, 20)
        }

        sos_button.setOnClickListener {
            if (ignoreClicks) {
                runCleanUp()
                callback?.removeHandlers()
            } else {
                val charMessage = arrayListOf('S', 'O', 'S')
                playWithFlash(charMessage, transmissionSpeed)
            }
        }

        reset_button.setOnClickListener {
            runCleanUp()
        }

        viewModel.cleanRunFlag.observe(viewLifecycleOwner, {
            runCleanUp()
        })
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            callback = context as FragmentCallbacks
            callback?.setCurrentFragment("Auto")
        } catch (castException: ClassCastException) {
            throw ClassCastException("Context does not implement $TAG callback")
        }
    }

    override fun onResume() {
        super.onResume()
        callback?.bindPreview(camera_preview, this@AutoDecodeFragment)
    }

    override fun onPause() {
        super.onPause()
        callback?.removeImageListener()
        callback?.resetCameraBinds()
        callback?.releaseWakeLock()
    }

    private fun decodeNotedTimings() {
        val timingsCopy = arrayListOf<Long>()
        timingsCopy.addAll(timings)
        val morseMessage = DecoderUtils.findMorseFromTimings(timings, diffTimings)
        if (morseMessage.isNotBlank()) {
            val decryptedMessage = if (!morseMessage.contains("-")) {
                // All the units are of same size, it could be . or -
                val dashedMessage = morseMessage.replace(".", "-")
                incoming_message.text = getString(
                    R.string.dot_message_or_dash_message, morseMessage, dashedMessage
                )
                getString(
                    R.string.dot_message_or_dash_message,
                    DecoderUtils.decryptMorse(morseMessage),
                    DecoderUtils.decryptMorse(dashedMessage)
                )
            } else {
                incoming_message.text = morseMessage
                DecoderUtils.decryptMorse(morseMessage)
            }
            decoded_message.text = decryptedMessage
            report_button.visible()
            report_button.setOnClickListener {
                activity?.askIfDecryptedCorrectly(decryptedMessage, timingsCopy)
            }
        }
    }

    private fun updateTimingViews() {
        val sb = StringBuilder()
        if (timings.size > 1) {
            timings.forEachIndexed { index, _ ->
                if (index == 0) return@forEachIndexed
                val diff = timings[index] - timings[index - 1]
                if (index % 2 == 0) {
                    sb.append("${String.format("%.1f", (diff / 1000f))}s(off)  ")
                } else {
                    sb.append("${String.format("%.1f", (diff / 1000f))}s(on)  ")
                }
            }
        }
        if (timings.size == 1) {
            decoded_message.text = ""
        }
        incoming_message.text = sb.toString().trim()
    }

    private fun removeHandlerCallbacks() {
        try {
            handler.removeCallbacksAndMessages(null)
        } catch (npe: NullPointerException) {
            FirebaseCrashlytics.getInstance().recordException(npe)
            //Log.d(TAG, "Error: ${npe.localizedMessage}")
        }
    }

    private fun runCleanUp() {
        ignoreClicks = false
        sos_button.text = getString(R.string.sos)
        start_stop_button.text = getString(R.string.start)
        sos_button.isEnabled = true
        signal_button.isEnabled = true
        report_button.gone()
        timings.clear()
        diffTimings.clear()
        incoming_message.text = ""
        decoded_message.text = ""
        avgLowLuminosity = 0.0
        avgHighLuminosity = 0.0
        avgCounter = 0
        startCapturing = false
        stopCapturingLowLuminosity = false
        start_timer.text = ""
        isFlashOn = false
        flash_status_view.gone()
    }

    private fun playWithFlash(charMessage: ArrayList<Char>, speed: Int) {
        // Setup, remove click listeners
        ignoreClicks = true
        report_button.gone()
        sos_button.text = getString(R.string.stop)
        start_stop_button.text = getString(R.string.stop)
        signal_button.isEnabled = false

        // Speed can be from 1 to 10, 3 means 1 unit = 3/3 sec, 10 means 1 unit = 3/10 sec
        // 1 means 1 unit = 3/1 sec. Default speed is 3 which means 1 sec = 1 unit.
        val transmissionSpeed: Float = 3f / speed
        val timeUnits = StringBuilder()
        val morseCode = StringBuilder()
        val charUnits = arrayListOf<Int>()
        val characters = arrayListOf<Char>()
        // Add character morse timings to string builder
        var index = 0
        for (char in charMessage) {
            if (char == ' ') {
                timeUnits.replace(timeUnits.length - 1, timeUnits.length, "")
            }
            timeUnits.append(charToUnits[char])
            morseCode.append(charToMorse[char])
            if (charUnits.isNotEmpty()) {
                if (char == ' ') {
                    charUnits.add(charToTotalUnits[char]!!)
                } else {
                    charUnits[index - 1] = charUnits[index - 1] + 3
                    charUnits.add(charToTotalUnits[char]!!)
                }
            } else {
                charUnits.add(charToTotalUnits[char]!!)
            }
            index++
        }
        // Remove last character because we have added 3 units for space after every character
        timeUnits.replace(timeUnits.length - 1, timeUnits.length, "")

        var delay = 0L
        val onOffDelays = arrayListOf<Long>()
        for (i in timeUnits.indices) {
            onOffDelays.add((delay * 1000 * transmissionSpeed).toLong())
            val unit = timeUnits[i].toString().toInt()
            delay += unit
        }

        isFlashOn = false
        callback?.playWithFlash(
            onOffDelays, charUnits, characters, speed,
            false, (delay * 1000 * transmissionSpeed).toLong()
        )
    }

    private val timer3Sec = Runnable {
        start_timer.text = getString(R.string.learning_low_luminosity, "3")
    }
    private val timer2Sec = Runnable {
        start_timer.text = getString(R.string.learning_low_luminosity, "2")
    }
    private val timer1Sec = Runnable {
        start_timer.text = getString(R.string.learning_low_luminosity, "1")
    }
    private val timer0Sec = Runnable {
        start_timer.text = ""
        stopCapturingLowLuminosity = true
    }

    override fun listenLuminosity(luminosity: Double) {
        if (startCapturing) {
            activity?.let {
                it.runOnUiThread {
                    avg_luminosity.text = getString(
                        R.string.average_current_luminosity,
                        String.format("%.1f", avgLowLuminosity),
                        String.format("%.1f", luminosity)
                    )
                }
            }
            if (!stopCapturingLowLuminosity) {
                avgLowLuminosity = (avgLowLuminosity * avgCounter + luminosity) / (avgCounter + 1)
                avgCounter++
            } else {
                avgCounter = 0
                if (luminosity > avgLowLuminosity * (1 + perceptibility / 100f) && !isFlashOn) {
                    isFlashOn = true
                    avgHighLuminosity =
                        (avgHighLuminosity * avgCounter + luminosity) / (avgCounter + 1)
                    avgCounter++
                    timings.add(System.currentTimeMillis())
                    if (timings.size > 1) {
                        diffTimings.add(timings[timings.size - 1] - timings[timings.size - 2])
                    }
                    activity?.let {
                        it.runOnUiThread {
                            flash_status_view.visible()
                            updateTimingViews()
                        }
                    }
                }
                /*
                    Choosing 50% decrement to be considered as flash off change.
                    Consider this case, avgLowLuminosity = 100, if perceptibility is set to 30%
                    when luminosity is 130, we consider it flash on. Suppose avgHighLuminosity
                    slightly decreases from 130 and becomes 128.
                    When flash goes off, luminosity will return to near 100
                    If we compare it with (1 + perceptibility%), required luminosity to call flash
                    off becomes 98.46, that means low luminosity has to drop further than what it was
                    when we started.
                 */
                else if (luminosity * (1 + perceptibility / 200f) <= avgHighLuminosity && isFlashOn) {
                    isFlashOn = false
                    timings.add(System.currentTimeMillis())
                    if (timings.size > 1) {
                        diffTimings.add(timings[timings.size - 1] - timings[timings.size - 2])
                    }
                    activity?.let {
                        it.runOnUiThread {
                            flash_status_view.gone()
                            updateTimingViews()
                        }
                    }
                }
            }
        } else {
            activity?.let {
                it.runOnUiThread {
                    avg_luminosity.text =
                        getString(R.string.average_luminosity, String.format("%.1f", luminosity))
                }
            }
        }
    }

    private fun setRectConstraints(value: Float) {
        when (value) {
            0.1f -> setGuidePercentage(0.45f, 0.55f)
            0.2f -> setGuidePercentage(0.4f, 0.6f)
            0.3f -> setGuidePercentage(0.35f, 0.65f)
            0.4f -> setGuidePercentage(0.3f, 0.7f)
            0.5f -> setGuidePercentage(0.25f, 0.75f)
        }
    }

    private fun setGuidePercentage(leftTopPerc: Float, rightBottomPerc: Float) {
        val leftParams = guide_left.layoutParams as ConstraintLayout.LayoutParams
        leftParams.guidePercent = leftTopPerc
        guide_left.layoutParams = leftParams
        val topParams = guide_top.layoutParams as ConstraintLayout.LayoutParams
        topParams.guidePercent = leftTopPerc
        guide_top.layoutParams = topParams
        val rightParams = guide_right.layoutParams as ConstraintLayout.LayoutParams
        rightParams.guidePercent = rightBottomPerc
        guide_right.layoutParams = rightParams
        val bottomParams = guide_bottom.layoutParams as ConstraintLayout.LayoutParams
        bottomParams.guidePercent = rightBottomPerc
        guide_bottom.layoutParams = bottomParams
    }
}