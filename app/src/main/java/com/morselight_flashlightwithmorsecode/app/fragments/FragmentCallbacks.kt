package com.morselight_flashlightwithmorsecode.app.fragments

import androidx.camera.view.PreviewView

interface FragmentCallbacks {
    fun switchTorch(torchOn: Boolean)
    fun removeHandlers()
    fun playWithFlash(
        onOffDelays: ArrayList<Long>, charUnits: ArrayList<Int>, characters: ArrayList<Char>,
        speed: Int, shouldSetCharChangeHandler: Boolean, finalOffDelay: Long
    )
    fun bindPreview(cameraPreview: PreviewView, imageAnalysisListener: ImageAnalysisListener)
    fun removeImageListener()
    fun resetCameraBinds()
    fun updateRectAreaPerc(percentage: Int)
    fun acquireWakeLock()
    fun releaseWakeLock()
    fun setCurrentFragment(fragment: String)
}

interface ImageAnalysisListener {
    fun listenLuminosity(luminosity: Double)
}