package dev.vexelray.demo.calculator;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the application knows, in one immutable value: the tape of lines entered so far, the arithmetic
 * the next line will be read in and the recursion limits it runs under, and what was wrong with the last line,
 * if anything was.
 *
 * <p>A snapshot rather than a set of fields somebody mutates, so the frame loop and the handlers can never see
 * half an edit. Add fields here rather than adding state elsewhere.
 */
record Doc(List<Entry> tape, String arithmetic, String limits, String error) {

    /**
     * One line of the tape: what was typed, what it answered, and the answer's other readings — and, when it left
     * exactly one name free, the curve over that name, as it was when the line was entered. Null otherwise.
     */
    record Entry(String input, String answer, String readings, Graph graph) {

        Entry(String input, String answer, String readings) {
            this(input, answer, readings, null);
        }
    }

    Doc {
        tape = List.copyOf(tape);
    }

    static Doc initial(String arithmetic, String limits) {
        return new Doc(List.of(), arithmetic, limits, "");
    }

    Doc with(Entry entry) {
        List<Entry> more = new ArrayList<>(tape);
        more.add(entry);
        return new Doc(more, arithmetic, limits, "");
    }

    Doc withArithmetic(String value) {
        return new Doc(tape, value, limits, error);
    }

    Doc withLimits(String value) {
        return new Doc(tape, arithmetic, value, error);
    }

    Doc withError(String value) {
        return new Doc(tape, arithmetic, limits, value);
    }
}
