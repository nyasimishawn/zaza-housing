package com.zaza.housing.util;

import java.awt.geom.AffineTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ExifOrientation {
    private ExifOrientation() { }

    public static int read(byte[] jpeg) {
        if (jpeg.length < 4 || unsigned(jpeg[0]) != 255 || unsigned(jpeg[1]) != 216) return 1;
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if (unsigned(jpeg[offset++]) != 255) return 1;
            while (offset < jpeg.length && unsigned(jpeg[offset]) == 255) offset++;
            if (offset >= jpeg.length) return 1;
            int marker = unsigned(jpeg[offset++]);
            if (marker == 218 || marker == 217) return 1;
            if (marker == 1 || (marker >= 208 && marker <= 215)) continue;
            if (offset + 2 > jpeg.length) return 1;
            int length = (unsigned(jpeg[offset]) << 8) | unsigned(jpeg[offset + 1]);
            int start = offset + 2, end = offset + length;
            if (length < 2 || end > jpeg.length) return 1;
            if (marker == 225 && length >= 16 && jpeg[start] == 'E' && jpeg[start + 1] == 'x'
                    && jpeg[start + 2] == 'i' && jpeg[start + 3] == 'f' && jpeg[start + 4] == 0 && jpeg[start + 5] == 0) {
                int tiff = start + 6;
                ByteOrder order;
                if (jpeg[tiff] == 'I' && jpeg[tiff + 1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
                else if (jpeg[tiff] == 'M' && jpeg[tiff + 1] == 'M') order = ByteOrder.BIG_ENDIAN;
                else return 1;
                var buffer = ByteBuffer.wrap(jpeg).order(order);
                if (Short.toUnsignedInt(buffer.getShort(tiff + 2)) != 42) return 1;
                long directoryOffset = tiff + Integer.toUnsignedLong(buffer.getInt(tiff + 4));
                if (directoryOffset < tiff + 8 || directoryOffset + 2 > end) return 1;
                int directory = (int) directoryOffset;
                int count = Short.toUnsignedInt(buffer.getShort(directory));
                if ((long) directory + 2 + count * 12L > end) return 1;
                for (int index = 0; index < count; index++) {
                    int entry = directory + 2 + index * 12;
                    if (Short.toUnsignedInt(buffer.getShort(entry)) == 274 && Short.toUnsignedInt(buffer.getShort(entry + 2)) == 3
                            && buffer.getInt(entry + 4) == 1) {
                        int orientation = Short.toUnsignedInt(buffer.getShort(entry + 8));
                        return orientation >= 1 && orientation <= 8 ? orientation : 1;
                    }
                }
            }
            offset = end;
        }
        return 1;
    }

    public static AffineTransform transform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }
    private static int unsigned(byte value) { return Byte.toUnsignedInt(value); }
}
