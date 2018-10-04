package com.google.flatbuffers.kt

/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.flatbuffers

import com.google.flatbuffers.Constants.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult

/// @cond FLATBUFFERS_INTERNAL

/**
 * All tables in the generated code derive from this class, and add their own accessors.
 */
class Table {
    /** Used to hold the position of the `bb` buffer.  */
    protected var bb_pos: Int = 0
    /** The underlying ByteBuffer to hold the data of the Table.  */
    /**
     * Get the underlying ByteBuffer.
     *
     * @return Returns the Table's ByteBuffer.
     */
    var byteBuffer: ByteBuffer
        protected set

    /**
     * Look up a field in the vtable.
     *
     * @param vtable_offset An `int` offset to the vtable in the Table's ByteBuffer.
     * @return Returns an offset into the object, or `0` if the field is not present.
     */
    protected fun __offset(vtable_offset: Int): Int {
        val vtable = bb_pos - byteBuffer.getInt(bb_pos)
        return (if (vtable_offset < byteBuffer.getShort(vtable)) byteBuffer.getShort(vtable + vtable_offset) else 0).toInt()
    }

    /**
     * Retrieve a relative offset.
     *
     * @param offset An `int` index into the Table's ByteBuffer containing the relative offset.
     * @return Returns the relative offset stored at `offset`.
     */
    protected fun __indirect(offset: Int): Int {
        return offset + byteBuffer.getInt(offset)
    }

    /**
     * Create a Java `String` from UTF-8 data stored inside the FlatBuffer.
     *
     * This allocates a new string and converts to wide chars upon each access,
     * which is not very efficient. Instead, each FlatBuffer string also comes with an
     * accessor based on __vector_as_bytebuffer below, which is much more efficient,
     * assuming your Java program can handle UTF-8 data directly.
     *
     * @param offset An `int` index into the Table's ByteBuffer.
     * @return Returns a `String` from the data stored inside the FlatBuffer at `offset`.
     */
    protected fun __string(offset: Int): String {
        var offset = offset
        val decoder = UTF8_DECODER.get()
        decoder.reset()

        offset += byteBuffer.getInt(offset)
        val src = byteBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val length = src.getInt(offset)
        src.position(offset + SIZEOF_INT)
        src.limit(offset + SIZEOF_INT + length)

        val required = (length.toFloat() * decoder.maxCharsPerByte()).toInt()
        var dst: CharBuffer? = CHAR_BUFFER.get()
        if (dst == null || dst!!.capacity() < required) {
            dst = CharBuffer.allocate(required)
            CHAR_BUFFER.set(dst)
        }

        dst!!.clear()

        try {
            val cr = decoder.decode(src, dst, true)
            if (!cr.isUnderflow()) {
                cr.throwException()
            }
        } catch (x: CharacterCodingException) {
            throw RuntimeException(x)
        }

        return dst!!.flip().toString()
    }

    /**
     * Get the length of a vector.
     *
     * @param offset An `int` index into the Table's ByteBuffer.
     * @return Returns the length of the vector whose offset is stored at `offset`.
     */
    protected fun __vector_len(offset: Int): Int {
        var offset = offset
        offset += bb_pos
        offset += byteBuffer.getInt(offset)
        return byteBuffer.getInt(offset)
    }

    /**
     * Get the start data of a vector.
     *
     * @param offset An `int` index into the Table's ByteBuffer.
     * @return Returns the start of the vector data whose offset is stored at `offset`.
     */
    protected fun __vector(offset: Int): Int {
        var offset = offset
        offset += bb_pos
        return offset + byteBuffer.getInt(offset) + SIZEOF_INT  // data starts after the length
    }

    /**
     * Get a whole vector as a ByteBuffer.
     *
     * This is efficient, since it only allocates a new [ByteBuffer] object,
     * but does not actually copy the data, it still refers to the same bytes
     * as the original ByteBuffer. Also useful with nested FlatBuffers, etc.
     *
     * @param vector_offset The position of the vector in the byte buffer
     * @param elem_size The size of each element in the array
     * @return The [ByteBuffer] for the array
     */
    protected fun __vector_as_bytebuffer(vector_offset: Int, elem_size: Int): ByteBuffer? {
        val o = __offset(vector_offset)
        if (o == 0) return null
        val bb = this.byteBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val vectorstart = __vector(o)
        bb.position(vectorstart)
        bb.limit(vectorstart + __vector_len(o) * elem_size)
        return bb
    }

    /**
     * Initialize vector as a ByteBuffer.
     *
     * This is more efficient than using duplicate, since it doesn't copy the data
     * nor allocattes a new [ByteBuffer], creating no garbage to be collected.
     *
     * @param bb The [ByteBuffer] for the array
     * @param vector_offset The position of the vector in the byte buffer
     * @param elem_size The size of each element in the array
     * @return The [ByteBuffer] for the array
     */
    protected fun __vector_in_bytebuffer(bb: ByteBuffer, vector_offset: Int, elem_size: Int): ByteBuffer? {
        val o = this.__offset(vector_offset)
        if (o == 0) return null
        val vectorstart = __vector(o)
        bb.rewind()
        bb.limit(vectorstart + __vector_len(o) * elem_size)
        bb.position(vectorstart)
        return bb
    }

    /**
     * Initialize any Table-derived type to point to the union at the given `offset`.
     *
     * @param t A `Table`-derived type that should point to the union at `offset`.
     * @param offset An `int` index into the Table's ByteBuffer.
     * @return Returns the Table that points to the union at `offset`.
     */
    protected fun __union(t: Table, offset: Int): Table {
        var offset = offset
        offset += bb_pos
        t.bb_pos = offset + byteBuffer.getInt(offset)
        t.byteBuffer = byteBuffer
        return t
    }

    /**
     * Sort tables by the key.
     *
     * @param offsets An 'int' indexes of the tables into the bb.
     * @param bb A `ByteBuffer` to get the tables.
     */
    protected fun sortTables(offsets: IntArray, bb: ByteBuffer) {
        val off = arrayOfNulls<Int>(offsets.size)
        for (i in offsets.indices) off[i] = offsets[i]
        java.util.Arrays.sort(off, object : java.util.Comparator<Int> {
            override fun compare(o1: Int?, o2: Int?): Int {
                return keysCompare(o1, o2, bb)
            }
        })
        for (i in offsets.indices) offsets[i] = off[i]
    }

    /**
     * Compare two tables by the key.
     *
     * @param o1 An 'Integer' index of the first key into the bb.
     * @param o2 An 'Integer' index of the second key into the bb.
     * @param bb A `ByteBuffer` to get the keys.
     */
    protected fun keysCompare(o1: Int?, o2: Int?, bb: ByteBuffer): Int {
        return 0
    }

    companion object {
        private val UTF8_DECODER = object : ThreadLocal<CharsetDecoder>() {
            protected override fun initialValue(): CharsetDecoder {
                return Charset.forName("UTF-8").newDecoder()
            }
        }
        val UTF8_CHARSET: ThreadLocal<Charset> = object : ThreadLocal<Charset>() {
            protected override fun initialValue(): Charset {
                return Charset.forName("UTF-8")
            }
        }
        private val CHAR_BUFFER = ThreadLocal<CharBuffer>()

        protected fun __offset(vtable_offset: Int, offset: Int, bb: ByteBuffer): Int {
            val vtable = bb.capacity() - offset
            return bb.getShort(vtable + vtable_offset - bb.getInt(vtable)) + vtable
        }

        protected fun __indirect(offset: Int, bb: ByteBuffer): Int {
            return offset + bb.getInt(offset)
        }

        /**
         * Check if a [ByteBuffer] contains a file identifier.
         *
         * @param bb A `ByteBuffer` to check if it contains the identifier
         * `ident`.
         * @param ident A `String` identifier of the FlatBuffer file.
         * @return True if the buffer contains the file identifier
         */
        protected fun __has_identifier(bb: ByteBuffer, ident: String): Boolean {
            if (ident.length != FILE_IDENTIFIER_LENGTH)
                throw AssertionError("FlatBuffers: file identifier must be length $FILE_IDENTIFIER_LENGTH")
            for (i in 0 until FILE_IDENTIFIER_LENGTH) {
                if (ident[i] != bb.get(bb.position() + SIZEOF_INT + i).toChar()) return false
            }
            return true
        }

        /**
         * Compare two strings in the buffer.
         *
         * @param offset_1 An 'int' index of the first string into the bb.
         * @param offset_2 An 'int' index of the second string into the bb.
         * @param bb A `ByteBuffer` to get the strings.
         */
        protected fun compareStrings(offset_1: Int, offset_2: Int, bb: ByteBuffer): Int {
            var offset_1 = offset_1
            var offset_2 = offset_2
            offset_1 += bb.getInt(offset_1)
            offset_2 += bb.getInt(offset_2)
            val len_1 = bb.getInt(offset_1)
            val len_2 = bb.getInt(offset_2)
            val startPos_1 = offset_1 + SIZEOF_INT
            val startPos_2 = offset_2 + SIZEOF_INT
            val len = Math.min(len_1, len_2)
            for (i in 0 until len) {
                if (bb.get(i + startPos_1) != bb.get(i + startPos_2))
                    return bb.get(i + startPos_1) - bb.get(i + startPos_2)
            }
            return len_1 - len_2
        }

        /**
         * Compare string from the buffer with the 'String' object.
         *
         * @param offset_1 An 'int' index of the first string into the bb.
         * @param key Second string as a byte array.
         * @param bb A `ByteBuffer` to get the first string.
         */
        protected fun compareStrings(offset_1: Int, key: ByteArray, bb: ByteBuffer): Int {
            var offset_1 = offset_1
            offset_1 += bb.getInt(offset_1)
            val len_1 = bb.getInt(offset_1)
            val len_2 = key.size
            val startPos_1 = offset_1 + Constants.SIZEOF_INT
            val len = Math.min(len_1, len_2)
            for (i in 0 until len) {
                if (bb.get(i + startPos_1) != key[i])
                    return bb.get(i + startPos_1) - key[i]
            }
            return len_1 - len_2
        }
    }
}

/// @endcond
