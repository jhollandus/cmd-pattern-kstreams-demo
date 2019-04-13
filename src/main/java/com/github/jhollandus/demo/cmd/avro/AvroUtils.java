package com.github.jhollandus.demo.cmd.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.jute.Index;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Function;

public class AvroUtils {
    private AvroUtils() {}

    public static boolean schemasMatch(SpecificRecord record, Schema schema) {
        return record.getSchema().getFullName().equals(schema.getFullName());
    }

    public static String avroToJson(SpecificRecord avroRec) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DatumWriter writer = new SpecificDatumWriter(avroRec.getClass());
            Encoder encoder = EncoderFactory.get().jsonEncoder(avroRec.getSchema(), baos);
            writer.write(avroRec, encoder);
            encoder.flush();
            return baos.toString();

        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<Schema> schemaFrom(Class<?> clazz) {
        try {
            Field schemaField = clazz.getDeclaredField("SCHEMA$");
            if(schemaField != null) {
                return Optional.ofNullable((Schema) schemaField.get(null));
            }
        } catch(Exception e) {
            //ignore
        }

        return Optional.empty();
    }

    public static <S> Extractor<IndexedRecord, S> extractorFor(
            String fieldName,
            Class<S> fieldType) {


        return record -> {

            Schema.Field field = record.getSchema().getField(fieldName);
            if (field != null) {
                Object fieldVal = record.get(field.pos());

                if (fieldType.isInstance(fieldVal)) {
                    return Optional.of(fieldType.cast(fieldVal));
                }
            }
            return Optional.empty();
        };
    }


    public interface Extractor<S extends IndexedRecord, T> {
        Optional<T> extractFieldValue(S record);

        default <TT> Extractor<S, TT> thenExtract(Extractor<? super T, TT> next) {
            return record  ->  extractFieldValue(record).flatMap(next::extractFieldValue);
        }
    }
}
