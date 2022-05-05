@file:Suppress("unused")

package top.anagke.auto_android.device

import org.tinylog.kotlin.Logger
import top.anagke.auto_android.device.TableSelector.ConstraintType.HORIZONTAL
import top.anagke.auto_android.device.TableSelector.ConstraintType.VERTICAL
import top.anagke.auto_android.img.Img
import top.anagke.auto_android.img.Tmpl
import top.anagke.auto_android.util.*
import java.time.Duration
import java.time.Instant
import kotlin.system.measureTimeMillis

class TimeoutException(message: String) : Exception(message)
class AssertException(message: String) : Exception(message)


private val lastMatchedTmplMap: MutableMap<Device, Tmpl?> = mutableMapOf()
private var Device.lastMatchedTmpl: Tmpl?
    get() {
        return lastMatchedTmplMap[this]
    }
    set(value) {
        lastMatchedTmplMap[this] = value
    }


fun Device.match(vararg tmpls: Tmpl): Boolean {
    return which(*tmpls) != null
}

fun Device.notMatch(vararg tmpls: Tmpl): Boolean {
    return which(*tmpls) == null
}

fun Device.which(vararg tmpls: Tmpl): Tmpl? {
    val screen: Img
    val capTime = measureTimeMillis { screen = cap() }
    for (tmpl in tmpls) {
        val diff: Double
        val matched: Boolean
        val diffTime = measureTimeMillis {
            diff = tmpl.diff(screen)
            matched = diff <= tmpl.threshold
        }
        Logger.debug("Matching $tmpl... result=$matched, difference=${diff.formatDiff()}, diffTime=$diffTime ms, capTime=$capTime ms")
        if (matched) {
            this.lastMatchedTmpl = tmpl
            return tmpl
        } else {
            this.lastMatchedTmpl = null
        }
    }
    return null
}


fun Device.await(vararg tmpls: Tmpl, timeout: Long = 1.minutes): Tmpl {
    Logger.debug("Awaiting ${tmpls.contentToString()}...")
    val frequency = newFrequency(timeout)
    val begin = Instant.now()
    var tmpl: Tmpl?
    do {
        tmpl = frequency.run { which(*tmpls) }
        if (Duration.between(begin, Instant.now()).toMillis() > timeout) {
            throw TimeoutException("timeout after $timeout ms")
        }
    } while (tmpl == null)
    return tmpl
}

private fun newFrequency(timeout: Long) = FrequencyLimiter(if (timeout <= 1.minutes) 1.seconds else 5.seconds)

fun Device.assert(vararg tmpls: Tmpl): Tmpl {
    Logger.debug("Asserting ${tmpls.toList()}...")
    val matched = which(*tmpls)
    if (matched != null) {
        return matched
    } else {
        throw AssertException("assert matching ${tmpls.contentToString()}")
    }
}


fun Device.whileMatch(vararg tmpls: Tmpl, timeout: Long = 1.minutes, block: () -> Unit) {
    val frequency = newFrequency(timeout)
    val begin = Instant.now()
    while (frequency.run { which(*tmpls) } != null) {
        block.invoke()
        if (Duration.between(begin, Instant.now()).toMillis() > timeout) {
            throw TimeoutException("timeout after $timeout ms")
        }
    }
}

fun Device.whileNotMatch(vararg tmpls: Tmpl, timeout: Long = 1.minutes, block: () -> Unit) {
    val frequency = newFrequency(timeout)
    val begin = Instant.now()
    while (frequency.run { which(*tmpls) } == null) {
        block.invoke()
        if (Duration.between(begin, Instant.now()).toMillis() > timeout) {
            throw TimeoutException("timeout after $timeout ms")
        }
    }
}


fun Device.matched(vararg tmpls: Tmpl): Boolean {
    if (tmpls.isEmpty()) return lastMatchedTmpl != null
    return lastMatchedTmpl in tmpls
}


fun Device.find(tmpl: Tmpl): Pos? {
    val screen = cap()
    val (pos, diff) = tmpl.find(screen)
    val result = if (diff <= tmpl.threshold) pos else null
    Logger.debug("Finding $tmpl... result=$result, difference=${diff.formatDiff()}")
    return result?.let { Rect(it, tmpl.img.size).center() }
}

fun Device.findEdge(tmpl: Tmpl): Pos? {
    val screen = cap()
    val (pos, diff) = tmpl.findEdge(screen)
    val result = if (diff <= tmpl.threshold) pos else null
    Logger.debug("Finding edge $tmpl... result=$result, difference=${diff.formatDiff()}")
    return result?.let { Rect(it, tmpl.img.size).center() }
}

fun Device.whileFind(tmpl: Tmpl, timeout: Long = 1.minutes, block: (Pos) -> Unit) {
    val frequency = newFrequency(timeout)
    val begin = Instant.now()
    var pos: Pos?
    while (frequency.run { find(tmpl) }.also { pos = it } != null) {
        block.invoke(pos!!)
        if (Duration.between(begin, Instant.now()).toMillis() > timeout) {
            throw TimeoutException("timeout after $timeout ms")
        }
    }
}

fun delay(time: Int) {
    Thread.sleep(time.toLong())
}

fun Unit.delay(time: Int) {
    Thread.sleep(time.toLong())
}

fun Unit.nap() {
    delay(1000)
}

fun Unit.sleep() {
    delay(2000)
}

fun Unit.sleepl() {
    delay(5000)
}

fun sleep() {
    delay(2000)
}


private fun Double.formatDiff() = "%.6f".format(this)


fun Device.tapdListItem(itemIndex: Int, itemLength: Int, x: Int, y: Int, dragDy: Int, tapDy: Int) {
    if (itemIndex < itemLength) {
        tapd(x, y + ((itemIndex) * tapDy)).nap()
    } else {
        repeat(itemIndex - (itemLength - 1)) {
            drag(x, y, x, y - dragDy)
        }
        tapd(x, y + ((itemLength - 1) * tapDy)).nap()
    }
}

data class TableSelector(
    val origin: Pos,
    val finale: Pos,
    val itemInterval: Size,
    val dragInterval: Size,
    val viewWidth: Int,
    val viewHeight: Int,
    val tableWidth: Int,
    val tableHeight: Int,
    val tableConstraintType: ConstraintType? = null,
    var viewX: Int = 0,
    var viewY: Int = 0,
) {

    enum class ConstraintType {
        HORIZONTAL, VERTICAL,
    }

    fun fromSeqNum(itemSeqNum: Int): Pos {
        tableConstraintType!!

        val tableConstraint = when (tableConstraintType) {
            HORIZONTAL -> tableWidth
            VERTICAL -> tableHeight
        }
        val seq = itemSeqNum % tableConstraint
        val carry = itemSeqNum / tableConstraint
        return when (tableConstraintType) {
            HORIZONTAL -> Pos(seq, carry)
            VERTICAL -> Pos(carry, seq)
        }
    }

    fun Device.tapItem(item: Pos) {
        var itemX = item.x
        var itemY = item.y
        if (itemX >= viewWidth) {
            val swipeCount = itemX - (viewWidth - 1) - viewX
            viewX += swipeCount
            repeat(swipeCount) {
                dragv(origin.x, origin.y, -dragInterval.width, 0)
            }
            if (viewX + viewWidth == tableWidth) {
                swipev(origin.x, origin.y, -dragInterval.width, 0, 1.0)
            }
            itemX = viewWidth - 1
        }
        if (itemY >= viewHeight) {
            val swipeCount = itemY - (viewHeight - 1) - viewY
            viewY += swipeCount
            repeat(swipeCount) {
                dragv(origin.x, origin.y, 0, -dragInterval.height)
            }
            if (viewY + viewHeight == tableHeight) {
                swipev(origin.x, origin.y, 0, -dragInterval.height, 1.0)
            }
            itemY = viewHeight - 1
        }
        val xOffset = if (viewX + viewWidth == tableWidth && itemX == viewWidth - 1) {
            finale.x
        } else {
            origin.x + itemX * itemInterval.width
        }
        val yOffset = if (viewY + viewHeight == tableHeight && itemY == viewHeight - 1) {
            finale.y
        } else {
            origin.y + itemY * itemInterval.height
        }
        tap(xOffset, yOffset)
    }

    fun Device.resetTable() {
        swipev(origin.x, origin.y, dragInterval.width * tableWidth, dragInterval.height * tableHeight, 3.0)
    }

}
