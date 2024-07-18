package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.Types.MinorType
import org.apache.arrow.vector.types.pojo.ArrowType

class ByteVector(allocator: BufferAllocator, override val name: String, override var nullable: Boolean) : FixedWidthVector(allocator) {

    override val arrowType: ArrowType = MinorType.TINYINT.type

    override fun writeNull() {
        super.writeNull()
        writeByte0(0)
    }

    override fun getByte(idx: Int) = getByte0(idx)
    override fun writeByte(value: Byte) = writeByte0(value)

    override fun getObject0(idx: Int) = getByte(idx)

    override fun writeObject0(value: Any) {
        if (value is Byte) writeByte(value) else TODO("not a Byte")
    }
}