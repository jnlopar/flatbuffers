package com.google.flatbuffers.kt

import kotlinx.io.charsets.Charset
import kotlinx.io.core.ByteOrder
import kotlinx.io.core.BytePacketBuilder
import kotlinx.io.core.IoBuffer
import kotlin.math.max

inline class OrDefault<T>(private val pair: Pair<T, T>) {
    val value
    get() = pair.first

    val default
    get() = pair.second
}

inline class Offset(val intValue: Int)
inline class StructOffset(val intValue: Int)

infix fun <T> T.orDefault(other: T) = OrDefault(this to other)

/**
 * Class that helps you build a FlatBuffer.  See the section
 * "Use in Java/C#" in the main FlatBuffers documentation.
 */
class FlatBufferBuilder(private val initialSize: Int?) {
    internal var bb: IoBuffer = IoBuffer.Pool.borrow()
    internal var space: Int = bb.capacity  // Remaining space in the head IoBuffer (bb).

    internal var minalign = 1               // Minimum alignment encountered so far.
    internal var vtable: IntArray? = null
    internal var vtableInUse = 0          // The amount of fields we're actually using.

    // Whether we are currently serializing a table.
    var nested = false
    private set

    // Whether the buffer is finished.
    var finished = false
    private set

    internal var object_start: Int = 0               // Starting offset of the current struct/table.
    internal var vtables = IntArray(16)    // List of offsets of all vtables.
    internal var num_vtables = 0            // Number of entries in `vtables` in use.
    internal var vector_num_elems = 0       // For the current vector being built.
    internal var forceDefaults = false // False omits default values from the serialized data.
    internal var encoder = utf8charset.newEncoder()
    internal var dst: IoBuffer? = null

    private fun seek(pos: Int) {
        if (bb.writeRemaining != bb.capacity) {
            bb.resetForWrite()
        }
        bb.reserveStartGap(pos)
    }

    /**
     * Offset relative to the end of the buffer.
     *
     * @return Offset relative to the end of the buffer.
     */
    fun offset(): Offset = Offset(bb.capacity - space)

    fun pad(byteSize: Int) {
        space -= byteSize
        (0 until byteSize).forEach { putByte(0.toByte()) }
        putByte(0.toByte())
    }

    /**
     * Prepare to write an element of `size` after `additional_bytes`
     * have been written, e.g. if you write a string, you need to align such
     * the int length field is aligned to [com.google.flatbuffers.SIZEOF_INT], and
     * the string data follows it directly.  If all you need to do is alignment, `additional_bytes`
     * will be 0.
     *
     * @param size This is the of the new element to write.
     * @param additional_bytes The padding size.
     */
    fun prep(size: Int, additional_bytes: Int = 0) {
        // Track the biggest thing we've ever aligned to.
        if (size > minalign) minalign = size
        // Find the amount of alignment needed such that `size` is properly
        // aligned after `additional_bytes`
        val align_size = (bb.capacity() - space + additional_bytes).inv() + 1 and size - 1
        // Reallocate the buffer if needed.
        while (space < align_size + size + additional_bytes) {
            val old_buf_size = bb.capacity()
            bb = growIoBuffer(bb, bb_factory)
            space += bb.capacity() - old_buf_size
        }
        pad(align_size)
    }

    fun putBoolean(x: Boolean) {
        bb.discard()
        bb.put(space -= SIZEOF_BYTE, (if (x) 1 else 0).toByte())
    }

    fun putByte(x: Byte) {
        bb.put(space -= SIZEOF_BYTE, x)
    }

    fun putShort(x: Short) {
        bb.putShort(space -= SIZEOF_SHORT, x)
    }

    fun putInt(x: Int) {
        bb.writeInt(space -= SIZEOF_INT, x)
        bb.putInt(space -= SIZEOF_INT, x)
    }

    fun putLong(x: Long) {
        bb.putLong(space -= SIZEOF_LONG, x)
    }

    fun putFloat(x: Float) {
        bb.putFloat(space -= SIZEOF_FLOAT, x)
    }

    /**
     * Add a `double` to the buffer, backwards from the current location. Doesn't align nor
     * check for space.
     *
     * @param x A `double` to put into the buffer.
     */
    fun putDouble(x: Double) {
        bb.putDouble(space -= SIZEOF_DOUBLE, x)
    }

    fun startVector(elem_size: Int, num_elems: Int, alignment: Int) = notNested {
        vector_num_elems = num_elems
        prep(SIZEOF_INT, elem_size * num_elems)
        prep(alignment, elem_size * num_elems) // Just in case alignment > int.
        nested = true
    }

    fun endVector(): Offset {
        if (!nested)
            throw AssertionError("FlatBuffers: endVector called without startVector")
        nested = false
        putInt(vector_num_elems)
        return offset()
    }
    /// @endcond

    fun createUnintializedVector(elem_size: Int, num_elems: Int, alignment: Int): IoBuffer {
        val length = elem_size * num_elems
        startVector(elem_size, num_elems, alignment)

        bb.position(space -= length)

        // Slice and limit the copy vector to point to the 'array'
        val copy = bb.slice().order(ByteOrder.LITTLE_ENDIAN)
        copy.limit(length)
        return copy
    }

    /**
     * Create a vector of tables.
     *
     * @param offsets Offsets of the tables.
     * @return Returns offset of the vector.
     */
    fun createVectorOfTables(offsets: IntArray): Int {
        notNested()
        startVector(SIZEOF_INT, offsets.size, Constants.SIZEOF_INT)
        for (i in offsets.indices.reversed()) addOffset(offsets[i])
        return endVector()
    }

    /**
     * Create a vector of sorted by the key tables.
     *
     * @param obj Instance of the table subclass.
     * @param offsets Offsets of the tables.
     * @return Returns offset of the sorted vector.
     */
    fun <T : Table> createSortedVectorOfTables(obj: T, offsets: IntArray): Int {
        obj.sortTables(offsets, bb)
        return createVectorOfTables(offsets)
    }

    /**
     * Encode the string `s` in the buffer using UTF-8.  If `s` is
     * already a [CharBuffer], this method is allocation free.
     *
     * @param s The string to encode.
     * @return The offset in the buffer where the encoded string starts.
     */
    fun createString(s: CharSequence): Int {
        val length = s.length
        val estimatedDstCapacity = (length * encoder.maxBytesPerChar()).toInt()
        if (dst == null || dst!!.capacity < estimatedDstCapacity) {
            dst = IoBuffer.allocate(max(128, estimatedDstCapacity))
        }

        dst!!.clear()

        val src = if (s is CharBuffer)
            s as CharBuffer
        else
            CharBuffer.wrap(s)
        val result = encoder.encode(src, dst, true)
        if (result.isError()) {
            try {
                result.throwException()
            } catch (x: CharacterCodingException) {
                throw Error(x)
            }

        }

        dst!!.flip()
        return createString(dst)
    }

    fun createString(s: IoBuffer): Int {
        val length = s!!.remaining()
        addByte(0.toByte())
        startVector(1, length, 1)
        bb.position(space -= length)
        bb.put(s!!)
        return endVector()
    }

    fun createByteVector(arr: ByteArray): Int {
        val length = arr.size
        startVector(1, length, 1)
        bb.position(space -= length)
        bb.put(arr)
        return endVector()
    }

    inline fun <T> finished(init: FlatBufferBuilder.() -> T): T = when(finished) {
        true -> init()
        false -> throw AssertionError(
            "FlatBuffers: you can only access the serialized buffer after it has been finished by "
            + "FlatBufferBuilder.finish().")
    }

    inline fun <T> notNested(init: FlatBufferBuilder.() -> T): T = when(nested) {
        true -> throw AssertionError("FlatBuffers: object serialization must not be nested.")
        false -> this.init()
    }

    inline fun <T> nested(obj: Offset, init: FlatBufferBuilder.() -> T): T = when(obj) {
        offset() -> this.init()
        else -> throw AssertionError("FlatBuffers: struct must be serialized inline.")
    }

    inline fun createObject(numfields: Int, init: FlatBufferBuilder.() -> Unit): Int = notNested {
        startObject(numfields)
        init()
        return endObject()
    }

    fun startObject(numfields: Int) = notNested {
        vtable
        if (vtable == null || vtable!!.size < numfields) vtable = IntArray(numfields)
        vtable_in_use = numfields
        Arrays.fill(vtable!!, 0, vtable_in_use, 0)
        nested = true
        object_start = offset()
    }

    private inline fun <T> T.prepThen(size: Int, init: (T) -> Unit): Unit {
        prep(size)
        init(this)
    }

    /**
     * Add an `int` to the buffer, properly aligned, and grows the buffer (if necessary).
     *
     * @param x An `int` to put into the buffer.
     */
    fun addInt(x: Int) {
        prep(SIZEOF_INT)
        putInt(x)
    }


    // "add" methods.

    operator fun plusAssign(x: Short) = x.prepThen(SIZEOF_SHORT, this::putShort)
    operator fun plusAssign(x: Boolean) = x.prepThen(SIZEOF_BYTE, this::putBoolean)
    operator fun plusAssign(x: Byte) = x.prepThen(SIZEOF_BYTE, this::putByte)
    operator fun plusAssign(x: Long) = x.prepThen(SIZEOF_LONG, this::putLong)
    operator fun plusAssign(x: Int) = x.prepThen(SIZEOF_INT, this::putInt)
    operator fun plusAssign(x: Double) = x.prepThen(SIZEOF_DOUBLE, this::putDouble)
    operator fun plusAssign(x: Float) = x.prepThen(SIZEOF_FLOAT, this::putFloat)
    operator fun plusAssign(off: Offset) {
        var off = off
        prep(SIZEOF_INT)  // Ensure alignment is already done.
        if (off <= offset()) {
            throw AssertionError()
        }
        off = offset() - off + SIZEOF_INT
        putInt(off.intValue)
    }

    // "add" methods with defaults.

    operator fun set(o: Offset, v: OrDefault<Short>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<Boolean>) = v.addAndSlot(o) {this += it }
    operator fun set(o: Offset, v: OrDefault<Byte>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<Long>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<Int>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<Float>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<Double>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<Offset>) = v.addAndSlot(o) { this += it }
    operator fun set(o: Offset, v: OrDefault<StructOffset>) = v.apply {
        if (value != default) {
            nested(Offset(v.value.intValue)) {
                slot(o)
            }
        }
    }

    private inline fun <T> OrDefault<T>.addAndSlot(
            offset: Offset, init: FlatBufferBuilder.(T) -> Unit): Unit {
        if (forceDefaults || value != default) {
            this@FlatBufferBuilder.init(value)
            slot(offset)
        }
    }

    fun slot(voffset: Offset) {
        vtable!![voffset.intValue] = offset().intValue
    }

    /**
     * Finish off writing the object that is under construction.
     *
     * @return The offset to the object inside [.dataBuffer].
     * @see .startObject
     */
    fun endObject(): Int {
        if (vtable == null || !nested)
            throw AssertionError("FlatBuffers: endObject called without startObject")
        addInt(0)
        val vtableloc = offset()
        // Write out the current vtable.
        var i = vtable_in_use - 1
        // Trim trailing zeroes.
        while (i >= 0 && vtable!![i] == 0) {
            i--
        }
        val trimmed_size = i + 1
        while (i >= 0) {
            // Offset relative to the start of the table.
            val off = (if (vtable!![i] != 0) vtableloc - vtable!![i] else 0).toShort()
            addShort(off)
            i--
        }

        val standard_fields = 2 // The fields below:
        addShort((vtableloc - object_start).toShort())
        addShort(((trimmed_size + standard_fields) * SIZEOF_SHORT).toShort())

        // Search for an existing vtable that matches the current one.
        var existing_vtable = 0
        i = 0
        outer_loop@ while (i < num_vtables) {
            val vt1 = bb.capacity() - vtables[i]
            val vt2 = space
            val len = bb.getShort(vt1)
            if (len == bb.getShort(vt2)) {
                var j = SIZEOF_SHORT
                while (j < len) {
                    if (bb.getShort(vt1 + j) != bb.getShort(vt2 + j)) {
                        i++
                        continue@outer_loop
                    }
                    j += SIZEOF_SHORT
                }
                existing_vtable = vtables[i]
                break@outer_loop
            }
            i++
        }

        if (existing_vtable != 0) {
            // Found a match:
            // Remove the current vtable.
            space = bb.capacity() - vtableloc
            // Point table to existing vtable.
            bb.putInt(space, existing_vtable - vtableloc)
        } else {
            // No match:
            // Add the location of the current vtable to the list of vtables.
            if (num_vtables == vtables.size) vtables = Arrays.copyOf(vtables, num_vtables * 2)
            vtables[num_vtables++] = offset()
            // Point table to current vtable.
            bb.putInt(bb.capacity() - vtableloc, offset() - vtableloc)
        }

        nested = false
        return vtableloc
    }

    /**
     * Checks that a required field has been set in a given table that has
     * just been constructed.
     *
     * @param table The offset to the start of the table from the `IoBuffer` capacity.
     * @param field The offset to the field in the vtable.
     */
    fun required(table: Int, field: Int) {
        val table_start = bb.capacity() - table
        val vtable_start = table_start - bb.getInt(table_start)
        val ok = bb.getShort(vtable_start + field).toInt() != 0
        // If this fails, the caller will show what field needs to be set.
        if (!ok)
            throw AssertionError("FlatBuffers: field $field must be set")
    }
    /// @endcond

    /**
     * Finalize a buffer, pointing to the given `root_table`.
     *
     * @param root_table An offset to be added to the buffer.
     * @param size_prefix Whether to prefix the size to the buffer.
     */
    protected fun finish(root_table: Int, size_prefix: Boolean) {
        prep(minalign, SIZEOF_INT + if (size_prefix) SIZEOF_INT else 0)
        addOffset(root_table)
        if (size_prefix) {
            addInt(bb.capacity() - space)
        }
        bb.position(space)
        finished = true
    }

    fun finish(root_table: Int) {
        finish(root_table, false)
    }

    fun finishSizePrefixed(root_table: Int) {
        finish(root_table, true)
    }

    protected fun finish(root_table: Int, file_identifier: String, size_prefix: Boolean) {
        prep(minalign, SIZEOF_INT + FILE_IDENTIFIER_LENGTH + if (size_prefix) SIZEOF_INT else 0)
        if (file_identifier.length != FILE_IDENTIFIER_LENGTH)
            throw AssertionError("FlatBuffers: file identifier must be length $FILE_IDENTIFIER_LENGTH")
        for (i in FILE_IDENTIFIER_LENGTH - 1 downTo 0) {
            addByte(file_identifier[i].toByte())
        }
        finish(root_table, size_prefix)
    }

    fun finish(root_table: Int, file_identifier: String) {
        finish(root_table, file_identifier, false)
    }

    fun finishSizePrefixed(root_table: Int, file_identifier: String) {
        finish(root_table, file_identifier, true)
    }

    fun forceDefaults(forceDefaults: Boolean): FlatBufferBuilder {
        this.forceDefaults = forceDefaults
        return this
    }

    fun dataBuffer(): IoBuffer = finished {
        bb ?: IoBuffer.Empty
        bb.
    }

    companion object {
        internal val utf8charset = Charset.forName("UTF-8") // The UTF-8 character set used by FlatBuffers.
    }

}
