package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap

/**
 * Reads the pet's spendable coin balance off the ROOM page's top bar.
 *
 * WHY THIS EXISTS. Care only buys a consumable when the pet is out of stock, so a purchase is the
 * one place care spends coins. Until now the app had no way to state what the pet actually HAS: the
 * session cap in [ExchangeBudget] says what this session is ALLOWED to spend, and the payroll award
 * line is money that arrived, not money on hand. Both were used as if they were a wallet. This reader
 * gives the purchase gate a real balance.
 *
 * WHERE. Measured on the room frame (display 4, 720x1600, 2026-10-21, evidence
 * `.build-tmp/device-acceptance/s21-reenter/round-0-initial-home.png`): the top bar shows three pills
 * left to right - 等级/经验 ("4" at x≈186), 爪印 ("11" at x≈378), 金币 ("86" at x≈523), all centred at
 * y≈260. The coin glyph sits at x≈455..495 and its number at x≈508..540, i.e. centred near
 * (0.727, 0.1625) of the frame. Bounds are stated as fractions so a calibrated resolution keeps
 * working.
 *
 * FAIL CLOSED. Any frame where the region holds no unambiguous digit string yields
 * [ObservedValue.Unknown]; it is never 0. A caller must refuse a purchase on Unknown rather than let
 * the game reject the payment and call that a policy.
 */
object RoomWalletParser {

    /** Centre of the coin number, as a fraction of the room frame (measured 2026-10-21). */
    const val COIN_CENTRE_X = 0.727f
    const val COIN_CENTRE_Y = 0.1625f
    /** Half-width/height of the accepted region around the centre, in frame fractions. */
    const val COIN_HALF_WIDTH = 0.048f
    const val COIN_HALF_HEIGHT = 0.019f

    private val DIGITS = Regex("""^\d{1,6}$""")

    /**
     * Picks the coin number out of one frame's recognised lines.
     *
     * A line counts only when its centre falls inside the coin region AND its text is nothing but
     * digits: the level pill ("4") and the paw pill ("11") sit further left, and a line that also
     * carries a glyph is not this number. More than one candidate inside the region is ambiguous and
     * reports Unknown rather than choosing.
     */
    fun parse(
        lines: List<TextLine>,
        frameWidth: Int,
        frameHeight: Int,
        observedAt: Long,
        evidence: String,
    ): ObservedValue<Int> {
        if (frameWidth != 720 || frameHeight != 1600) {
            return ObservedValue.Unknown("WALLET_FRAME_SIZE_INVALID", observedAt, evidence)
        }
        val cx = COIN_CENTRE_X * frameWidth
        val cy = COIN_CENTRE_Y * frameHeight
        val halfW = COIN_HALF_WIDTH * frameWidth
        val halfH = COIN_HALF_HEIGHT * frameHeight
        val candidates = lines.mapNotNull { line ->
            val bounds = line.bounds
            val lineCx = (bounds.left + bounds.right) / 2f
            val lineCy = (bounds.top + bounds.bottom) / 2f
            val inside = kotlin.math.abs(lineCx - cx) <= halfW && kotlin.math.abs(lineCy - cy) <= halfH
            val text = line.text.trim()
            if (!inside || !DIGITS.matches(text)) null else text.toIntOrNull()
        }
        return when {
            candidates.size == 1 -> ObservedValue.Known(
                candidates.single(), observedAt, 1.0, evidence, ObservationSource.DIGIT_RECOGNITION)
            candidates.isEmpty() ->
                ObservedValue.Unknown("WALLET_COIN_REGION_UNREAD", observedAt, evidence)
            else -> ObservedValue.Unknown(
                "WALLET_COIN_REGION_AMBIGUOUS:${candidates.joinToString("/")}", observedAt, evidence)
        }
    }
}

/** The sheet's own coin pill, measured in the local s21 before-pay frame (86 at x≈655/y880). */
object ExchangeWalletParser {
    fun roiBounds() = android.graphics.Rect(628, 848, 692, 915)
    fun parse(lines: List<TextLine>, frameWidth: Int, frameHeight: Int, observedAt: Long,
        evidence: String, pageIdentityVerified: Boolean): ObservedValue<Int> {
        if (!pageIdentityVerified) return ObservedValue.Unknown("WALLET_DIALOG_UNCONFIRMED",observedAt,evidence)
        if(frameWidth!=720 || frameHeight!=1600)
            return ObservedValue.Unknown("WALLET_FRAME_SIZE_INVALID",observedAt,evidence)
        val candidates=lines.filter { line ->
            val b=line.bounds
            b.left>628 && b.right<692 && b.top>848 && b.bottom<915 &&
                b.left<b.right && b.top<b.bottom && Regex("^[0-9]{1,6}$").matches(line.text.trim())
        }.mapNotNull { it.text.trim().toIntOrNull() }
        return when(candidates.size) {
            1 -> ObservedValue.Known(candidates.single(),observedAt,1.0,evidence,ObservationSource.DIGIT_RECOGNITION)
            0 -> ObservedValue.Unknown("WALLET_DIALOG_COIN_UNREAD",observedAt,evidence)
            else -> ObservedValue.Unknown("WALLET_DIALOG_COIN_AMBIGUOUS",observedAt,evidence)
        }
    }
}

/** Local OCR only. The caller has verified room identity, or passes same-frame dialog proof. */
class DeviceWalletReader(private val reader: CourseNameReader) : WalletReader {
    override fun read(openedFrame: Bitmap, observedAt: Long, evidence: String): ObservedValue<Int> =
        readLocal(openedFrame,android.graphics.Rect(500,230,566,292),observedAt,evidence) { lines ->
            RoomWalletParser.parse(lines,openedFrame.width,openedFrame.height,observedAt,evidence)
        }

    fun readExchange(openedFrame: Bitmap, observedAt: Long, evidence: String,
        pageIdentityVerified: Boolean): ObservedValue<Int> {
        if(!pageIdentityVerified) return ObservedValue.Unknown("WALLET_DIALOG_UNCONFIRMED",observedAt,evidence)
        if(openedFrame.width!=720 || openedFrame.height!=1600)
            return ObservedValue.Unknown("WALLET_FRAME_SIZE_INVALID",observedAt,evidence)
        // Full-frame OCR then ROI filter: the sheet coin pill is small and ML Kit often misses the
        // number inside a 64x67 crop even when the digit is visually clear (measured 2026-10-22
        // wallet crop of "36"). Room identity is still rejected by the dialog ROI.
        return try {
            ExchangeWalletParser.parse(reader.read(openedFrame),openedFrame.width,openedFrame.height,
                observedAt,evidence,true)
        } catch(t: Exception) {
            if(t is kotlinx.coroutines.CancellationException) throw t
            ObservedValue.Unknown("WALLET_OCR_FAILED:${t.javaClass.simpleName}",observedAt,evidence)
        }
    }

    private fun readLocal(frame: Bitmap, roi: android.graphics.Rect, observedAt: Long, evidence: String,
        parse: (List<TextLine>)->ObservedValue<Int>): ObservedValue<Int> {
        if(frame.width!=720 || frame.height!=1600)
            return ObservedValue.Unknown("WALLET_FRAME_SIZE_INVALID",observedAt,evidence)
        val crop=Bitmap.createBitmap(frame,roi.left,roi.top,roi.width(),roi.height())
        return try {
            val local=reader.read(crop)
            val clipped=local.any { line ->
                Regex("^[0-9]{1,6}$").matches(line.text.trim()) &&
                    (line.bounds.left<=0 || line.bounds.top<=0 || line.bounds.right>=crop.width || line.bounds.bottom>=crop.height)
            }
            if(clipped) ObservedValue.Unknown("WALLET_CROP_TRUNCATED_DIGIT",observedAt,evidence)
            else parse(local.map { line -> TextLine(line.text,android.graphics.Rect(
                line.bounds.left+roi.left,line.bounds.top+roi.top,
                line.bounds.right+roi.left,line.bounds.bottom+roi.top)) })
        } catch(t: Exception) {
            if(t is kotlinx.coroutines.CancellationException) throw t
            ObservedValue.Unknown("WALLET_OCR_FAILED:${t.javaClass.simpleName}",observedAt,evidence)
        } finally { if(crop!==frame) crop.recycle() }
    }
}

/** One way to learn the pet's spendable coins. Unknown means "not read", never zero. */
fun interface WalletReader {
    fun read(openedFrame: Bitmap, observedAt: Long, evidence: String): ObservedValue<Int>
}
