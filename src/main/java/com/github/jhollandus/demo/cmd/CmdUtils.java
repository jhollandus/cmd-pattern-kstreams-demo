package com.github.jhollandus.demo.cmd;

import com.github.jhollandus.demo.cmd.avro.AvroUtils;
import org.apache.avro.generic.IndexedRecord;

import javax.activation.CommandInfo;

public class CmdUtils {
    private CmdUtils() {
    }

    public static AvroUtils.Extractor<IndexedRecord, User> USER_EXTRACTOR =
            AvroUtils.extractorFor("user", User.class);

    public static AvroUtils.Extractor<IndexedRecord, CmdInfo> CMD_INFO_EXTRACTOR =
            AvroUtils.extractorFor("cmdInfo", CmdInfo.class);

 }
