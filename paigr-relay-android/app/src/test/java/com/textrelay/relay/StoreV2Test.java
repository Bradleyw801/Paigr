package com.textrelay.relay;

/** Every StoreTest against a private inbox (v2). */
public class StoreV2Test extends StoreTest {
    @Override
    protected boolean v2() {
        return true;
    }
}
