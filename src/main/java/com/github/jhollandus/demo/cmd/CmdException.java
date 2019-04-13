package com.github.jhollandus.demo.cmd;

public class CmdException extends RuntimeException {
    private final CmdError error;

    public CmdException(CmdError error) {
        super(error.toString());
        this.error = error;
    }

    public CmdError getError() {
        return error;
    }
}
