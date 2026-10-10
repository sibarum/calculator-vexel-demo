package dev.vexelray.demo.calculator;

import sibarum.cott.calculator.Mode;
import sibarum.cott.calculator.Modeset;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the application knows, in one immutable value: the tape of lines entered so far, the mode of each
 * of cott-engine's modesets the next line will run in (its number type, its recursion limits, and whatever else
 * the engine comes to offer), and what was wrong with the last line, if anything was.
 *
 * <p>A snapshot rather than a set of fields somebody mutates, so the frame loop and the handlers can never see
 * half an edit. Add fields here rather than adding state elsewhere.
 */
record Doc(List<Entry> tape, Map<Modeset, Mode> modes, String error) {

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
        modes = Map.copyOf(modes);
    }

    static Doc initial(Map<Modeset, Mode> modes) {
        return new Doc(List.of(), modes, "");
    }

    /** The mode {@code modeset} is in. */
    Mode mode(Modeset modeset) {
        return modes.get(modeset);
    }

    Doc with(Entry entry) {
        List<Entry> more = new ArrayList<>(tape);
        more.add(entry);
        return new Doc(more, modes, "");
    }

    Doc with(Mode mode) {
        Map<Modeset, Mode> changed = new EnumMap<>(modes);
        changed.put(mode.modeset(), mode);
        return new Doc(tape, changed, error);
    }

    Doc withError(String value) {
        return new Doc(tape, modes, value);
    }
}
