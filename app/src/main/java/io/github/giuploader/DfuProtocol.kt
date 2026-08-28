package io.github.giuploader

object DfuProtocol {
    const val USB_OUT_CLASS_INTERFACE = 0x21
    const val USB_IN_CLASS_INTERFACE = 0xa1
    const val DNLOAD = 1
    const val UPLOAD = 2
    const val GETSTATUS = 3
    const val CLRSTATUS = 4
    const val ABORT = 6
    const val DFU_IDLE = 2
    const val DNLOAD_SYNC = 3
    const val DNBUSY = 4
}
