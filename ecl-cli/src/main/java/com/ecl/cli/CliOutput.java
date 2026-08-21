package com.ecl.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

/** Formats human-readable and machine-readable command output consistently. */
final class CliOutput {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private CliOutput() {
    }

    static void print(Object value, boolean json) {
        if (!json) {
            if (value instanceof Iterable<?> iterable) {
                iterable.forEach(System.out::println);
            } else {
                System.out.println(value);
            }
            return;
        }
        System.out.println(toJson(value));
    }

    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(new EclCli()), "Unable to encode JSON", exception);
        }
    }
}
