package io.github.rastislavpapp.logxy.core.template

/**
 * A slot's values summed up per slice of time, [count] slices of [width] nanoseconds from [from]; see
 * [SlotValues.slices]. The statistics are of the slice's numbers and NaN where it has none.
 */
class SlotSlices(val from: Long, val width: Long, val count: Int) {
    /** Records of the template, with a number or without. */
    val records = IntArray(count)

    /** Records whose slot holds no number. */
    val withoutNumber = IntArray(count)

    val min = DoubleArray(count) { Double.NaN }
    val p50 = DoubleArray(count) { Double.NaN }
    val p99 = DoubleArray(count) { Double.NaN }
    val max = DoubleArray(count) { Double.NaN }
    val sum = DoubleArray(count) { Double.NaN }
    val average = DoubleArray(count) { Double.NaN }
}
