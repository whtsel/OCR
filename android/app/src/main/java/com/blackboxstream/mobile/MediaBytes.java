package com.blackboxstream.mobile;

import java.io.Closeable;
import java.io.IOException;

interface MediaBytes extends Closeable {
    long size();
    int read(long offset, byte[] data, int start, int count) throws IOException;
}
