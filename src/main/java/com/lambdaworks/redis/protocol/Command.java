// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis.protocol;

import com.lambdaworks.redis.RedisCommandInterruptedException;
import org.jboss.netty.buffer.ChannelBuffer;

import java.util.concurrent.*;

/**
 * A redis command and its result. All successfully executed commands will
 * eventually return a {@link CommandOutput} object.
 *
 * @param <T> Command output type.
 *
 * @author Will Glozer
 */
public class Command<K, V, T> implements Future<T> {
    protected static final byte[] CRLF = "\r\n".getBytes();

    protected final CommandType type;
    protected CommandArgs args;
    protected CommandOutput<K, V, T> output;
    protected CountDownLatch latch;

    /**
     * Create a new command with the supplied type and args.
     *
     * @param type      Command type.
     * @param output    Command output.
     * @param args      Command args, if any.
     */
    public Command(CommandType type, CommandOutput<K, V, T> output, CommandArgs args) {
        this.type   = type;
        this.output = output;
        this.args   = args;
        this.latch  = new CountDownLatch(1);
    }

    /**
     * Cancel the command and notify any waiting consumers. This does
     * not cause the redis server to stop executing the command.
     *
     * @param ignored Ignored parameter.
     *
     * @return true if the command was cancelled.
     */
    @Override
    public boolean cancel(boolean ignored) {
        boolean cancelled = false;
        if (latch.getCount() == 1) {
            latch.countDown();
            cancelled = true;
        }
        return cancelled;
    }

    /**
     * Check if the command has been cancelled.
     *
     * @return True if the command was cancelled.
     */
    @Override
    public boolean isCancelled() {
        return latch.getCount() == 0 && output == null;
    }

    /**
     * Check if the command has completed.
     *
     * @return true if the command has completed.
     */
    @Override
    public boolean isDone() {
        return latch.getCount() == 0;
    }

    /**
     * Get the command output and if the command hasn't completed
     * yet, wait until it does.
     *
     * @return The command output.
     */
    @Override
    public T get() {
        try {
            latch.await();
            return output.get();
        } catch (InterruptedException e) {
            throw new RedisCommandInterruptedException(e);
        }
    }

    /**
     * Get the command output and if the command hasn't completed yet,
     * wait up to the specified time until it does.
     *
     * @param timeout   Maximum time to wait for a result.
     * @param unit      Unit of time for the timeout.
     *
     * @return The command output, or null if the timeout expires.
     */
    @Override
    public T get(long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit) ? output.get() : null;
        } catch (InterruptedException e) {
            throw new RedisCommandInterruptedException(e);
        }
    }

    /**
     * Wait up to the specified time for the command output to become
     * available.
     *
     * @param timeout   Maximum time to wait for a result.
     * @param unit      Unit of time for the timeout.
     *
     * @return true if the output became available.
     */
    public boolean await(long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            throw new RedisCommandInterruptedException(e);
        }
    }

    /**
     * Get the object that holds this command's output.
     *
     * @return  The command output object.
     */
    public CommandOutput<K, V, T> getOutput() {
        return output;
    }

    /**
     * Mark this command complete and notify all waiting threads.
     */
    public void complete() {
        latch.countDown();
    }

    /**
     * Encode and write this command to the supplied buffer using the new
     * <a href="http://redis.io/topics/protocol">Unified Request Protocol</a>.
     *
     * @param buf Buffer to write to.
     */
    void encode(ChannelBuffer buf) {
        buf.writeByte('*');
        writeInt(buf, 1 + (args != null ? args.count() : 0));
        buf.writeBytes(CRLF);
        buf.writeByte('$');
        writeInt(buf, type.bytes.length);
        buf.writeBytes(CRLF);
        buf.writeBytes(type.bytes);
        buf.writeBytes(CRLF);
        if (args != null) {
            buf.writeBytes(args.buffer());
        }
    }

    /**
     * Write the textual value of a positive integer to the supplied buffer.
     *
     * @param buf   Buffer to write to.
     * @param value Value to write.
     */
    protected static void writeInt(ChannelBuffer buf, int value) {
        if (value < 10) {
            buf.writeByte('0' + value);
            return;
        }

        StringBuilder sb = new StringBuilder(8);
        while (value > 0) {
            int digit = value % 10;
            sb.append((char) ('0' + digit));
            value /= 10;
        }

        for (int i = sb.length() - 1; i >= 0; i--) {
            buf.writeByte(sb.charAt(i));
        }
    }
}
