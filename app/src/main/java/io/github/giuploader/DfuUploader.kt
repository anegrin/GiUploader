package io.github.giuploader

import android.hardware.usb.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DfuUploader(
    private val connection: UsbDeviceConnection,
    device: UsbDevice,
    private val profile: Stm32BoardProfile
) {
    private val intf = (0 until device.interfaceCount)
        .map { device.getInterface(it) }
        .firstOrNull { it.interfaceClass == 0xfe && it.interfaceSubclass == 1 }
        ?: error("No STM32 DFU interface found")
    // STM32 DFU uses control transfers; the DFU interface commonly has no data endpoints.
    private val timeout = 10000
    // DFU block addressing uses the device's fixed descriptor transfer size,
    // not the host buffer size. Query it instead of assuming 1 or 2 KiB.
    private val transferSize by lazy { queryTransferSize() }
    fun upload(elf: ByteArray, massErase: Boolean, progress: (Int, String) -> Unit) {
        require(connection.claimInterface(intf, true)) {
            "Could not claim STM32 DFU interface"
        }
        resetDfuState()
        val segments = Elf32.loadSegments(elf)
        require(segments.isNotEmpty()) { "ELF contains no loadable firmware" }
        var done = 0L
        val total = segments.sumOf { it.data.size.toLong() }
        // The global mass-erase command is rejected by a number of STM32
        // bootloaders. Erase the affected sectors explicitly for both modes.
        eraseSectors(segments, massErase, progress)
        val chunkSize = transferSize
        progress(20, "DFU ready • transfer size ${formatKb(chunkSize.toLong())} KB")
        var finalBlock = 2
        segments.forEach { segment ->
            setAddress(segment.address)
            var block = 2
            segment.data.asList().chunked(transferSize).forEach { part ->
                dnload(block++, part.toByteArray())
                done += part.size
                progress(
                    (20 + done * 65 / total).toInt(),
                    "Writing firmware • ${formatKb(done)} / ${formatKb(total)} KB"
                )
            }
            finalBlock = block
        }
        progress(98, "Finalizing firmware…")
        resetDfuState()
        dnload(finalBlock, ByteArray(0), checkStatus = false)
        getStatus("finalize firmware")
        progress(100, "Firmware upload complete")
    }
    private fun setAddress(address: Long) {
        val buffer = ByteBuffer.allocate(5).order(profile.byteOrder)
        buffer.put(profile.setAddressCommand)
        buffer.putInt(address.toInt())
        command(buffer.array(), "set address 0x${address.toString(16)}")
    }
    private fun eraseSectors(segments: List<Segment>, eraseAllFlash: Boolean, progress: (Int, String) -> Unit) {
        val eraseRequests = if (eraseAllFlash) {
            eraseAddresses(profile.flashStartAddress, profile.flashEndAddress)
        } else {
            segments.flatMap { eraseAddresses(it.address, it.address + it.data.size) }.distinct().sorted()
        }
        val totalBytes = if (eraseAllFlash) profile.flashSizeBytes else segments.sumOf { it.data.size.toLong() }
        eraseRequests.forEachIndexed { index, address ->
            val b = ByteBuffer.allocate(5).order(profile.byteOrder)
            b.put(profile.eraseCommand)
            b.putInt(address.toInt())
            command(b.array(), "erase sector 0x${address.toString(16)}")
            progress(
                ((index + 1) * 20 / eraseRequests.size).coerceAtMost(20),
                "Erasing flash • ${formatKb(totalBytes)} KB • page ${index + 1}/${eraseRequests.size}"
            )
        }
    }

    private fun formatKb(bytes: Long): String {
        return "%.1f".format(java.util.Locale.US, bytes / 1024.0)
    }
    private fun queryTransferSize(): Int {
        val header = ByteArray(9)
        val headerLength = connection.controlTransfer(0x80, 6, 0x0200, 0, header, header.size, timeout)
        if (headerLength < 9) return profile.defaultTransferSizeBytes
        val totalLength = (header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)
        if (totalLength < 9 || totalLength > 4096) return profile.defaultTransferSizeBytes
        val descriptor = ByteArray(totalLength)
        val length = connection.controlTransfer(0x80, 6, 0x0200, 0, descriptor, descriptor.size, timeout)
        if (length < 9) return profile.defaultTransferSizeBytes

        var offset = 0
        while (offset + 2 <= length) {
            val size = descriptor[offset].toInt() and 0xff
            val type = descriptor[offset + 1].toInt() and 0xff
            if (size < 2 || offset + size > length) break
            if (type == 0x21 && size >= 7) {
                val value = (descriptor[offset + 5].toInt() and 0xff) or
                    ((descriptor[offset + 6].toInt() and 0xff) shl 8)
                if (value in 2..2048) return value
            }
            offset += size
        }
        return profile.defaultTransferSizeBytes
    }
    /** STM32F072 uses 2 KiB flash pages. DFU exposes erase-by-address, not geometry. */
    private fun eraseAddresses(start: Long, endExclusive: Long): List<Long> {
        require(start >= profile.flashStartAddress && endExclusive <= profile.flashEndAddress) {
            "Firmware address range 0x${start.toString(16)}..0x${endExclusive.toString(16)} " +
                "is outside ${profile.displayName} flash"
        }
        val result = mutableListOf<Long>()
        var address = start and -(profile.pageSizeBytes)
        while (address < endExclusive) {
            result += address
            address += profile.pageSizeBytes
        }
        return result
    }
    private fun command(data: ByteArray, message: String) {
        dnload(0, data, checkStatus = false)
        getStatus(message)
    }
    private fun resetDfuState() {
        // A failed transfer leaves the bootloader in dfuERROR. Return it to dfuIDLE
        // before issuing the DfuSe address or erase command.
        var status = readStatus()
        if (status.code != 0) {
            controlRequest(DfuProtocol.CLRSTATUS, "clear DFU error")
            status = readStatus()
        }
        if (status.state != 2) {
            controlRequest(DfuProtocol.ABORT, "abort DFU transfer")
            status = readStatus()
        }
        if (status.code != 0) {
            controlRequest(DfuProtocol.CLRSTATUS, "clear DFU error")
            status = readStatus()
        }
        require(status.code == 0 && status.state == 2) {
            "Could not reset DFU state (status=${status.code}, state=${status.state})"
        }
    }

    private fun controlRequest(request: Int, operation: String) {
        val result = connection.controlTransfer(DfuProtocol.USB_OUT_CLASS_INTERFACE, request, 0, intf.id, ByteArray(0), 0, timeout)
        require(result >= 0) { "DFU $operation request failed" }
    }

    private data class DfuStatus(val code: Int, val state: Int)

    private fun readStatus(): DfuStatus {
        val bytes = ByteArray(6)
        require(
            connection.controlTransfer(
                DfuProtocol.USB_IN_CLASS_INTERFACE,
                DfuProtocol.GETSTATUS,
                0,
                intf.id,
                bytes,
                6,
                timeout
            ) == 6
        ) {
            "Could not read DFU status"
        }
        return DfuStatus(bytes[0].toInt() and 0xff, bytes[4].toInt() and 0xff)
    }
    private fun dnload(block: Int, data: ByteArray, checkStatus: Boolean = true) {
        val length = data.size
        val transferred = connection.controlTransfer(
            DfuProtocol.USB_OUT_CLASS_INTERFACE,
            DfuProtocol.DNLOAD,
            block,
            intf.id,
            data,
            length,
            timeout
        )
        if (transferred != length) {
            error(
                "DFU download request failed " +
                    "(block=$block, length=$length, transferred=$transferred)"
            )
        }
        if (length > 0 && checkStatus) {
            getStatus("write block $block")
        }
    }

    private fun getStatus(operation: String = "DFU transfer") {
        repeat(30) {
            val status = ByteArray(6)
            val received = connection.controlTransfer(
                DfuProtocol.USB_IN_CLASS_INTERFACE,
                DfuProtocol.GETSTATUS,
                0,
                intf.id,
                status,
                6,
                timeout
            )
            if (received < 6) {
                error("Could not read DFU status during $operation")
            }
            if (status[0].toInt() != 0) {
                error("DFU device reported status ${status[0].toInt()} during $operation")
            }

            val delay = (status[1].toInt() and 255) or
                ((status[2].toInt() and 255) shl 8) or
                ((status[3].toInt() and 255) shl 16)
            if (status[4].toInt() !in 3..4) {
                return
            }
            Thread.sleep(delay.coerceAtMost(1000).toLong())
        }
        error("DFU device did not become ready during $operation")
    }
}

private data class Segment(val address: Long, val data: ByteArray)
private object Elf32 {
    fun loadSegments(bytes: ByteArray): List<Segment> {
        require(
            bytes.size > 52 &&
                bytes[0] == 0x7f.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[4].toInt() == 1
        ) { "Only 32-bit ELF files are supported" }

        fun uint(offset: Int): Long = ByteBuffer
            .wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL

        val programHeaderOffset = uint(28).toInt()
        val programHeaderSize = (bytes[42].toInt() and 0xff) or
            ((bytes[43].toInt() and 0xff) shl 8)
        val programHeaderCount = (bytes[44].toInt() and 0xff) or
            ((bytes[45].toInt() and 0xff) shl 8)

        return (0 until programHeaderCount).mapNotNull { index ->
            val header = programHeaderOffset + index * programHeaderSize
            if (uint(header) != 1L) {
                null
            } else {
                val fileOffset = uint(header + 4).toInt()
                // ELF32 program headers store the physical load address at +12.
                // Some embedded toolchains leave it zero, so use the virtual
                // address at +8 as a fallback.
                val virtualAddress = uint(header + 8)
                val physicalAddress = uint(header + 12)
                val address = if (physicalAddress != 0L) physicalAddress else virtualAddress
                val length = uint(header + 16).toInt()
                require(fileOffset >= 0 && length >= 0 && fileOffset + length <= bytes.size) {
                    "Invalid ELF segment"
                }
                Segment(address, bytes.copyOfRange(fileOffset, fileOffset + length))
            }
        }
    }
}
