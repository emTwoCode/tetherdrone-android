package com.zuehlke.tetherdrone;

/**
 * Created by mis on 24.09.13.
 */
public class CancelableThread extends Thread {

    protected boolean shouldStop;

    public void cancel() {
        this.shouldStop = true;
    }
}
