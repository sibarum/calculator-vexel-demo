package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;

import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

/**
 * How this calculator moves: three durations, and the four things it has to say without words.
 *
 * <h2>Two durations, because there are two kinds of event</h2>
 *
 * A <b>transition</b> is a change between two states the eye can look at -- a pad sliding in, a status line
 * arriving. Both ends are worth seeing, so the only question is whether the change reads as continuous, and
 * {@link #TRANSITION} is about as short as that can be.
 *
 * <p>A <b>cue</b> has no end state at all. It exists only in the middle: a line sweeps down a field and is
 * gone, a ring pulses and is gone. The question is not "did it read as continuous" but "was it noticed", and
 * that threshold is the higher of the two -- hence {@link #CUE}. A refusal gets {@link #ALERT}, longer again,
 * because it is asking to be read rather than merely noticed.
 *
 * <h2>Linear, except where something arrives somewhere</h2>
 *
 * Every ramp here is {@link Ease#LINEAR} and only one thing is eased. Easing exists for something arriving at
 * a <em>place</em>, where decelerating into it reads as weight. A dissolve has no place to arrive at and
 * neither does a sweep, and an {@code OUT_CUBIC} ramp is 87% through by the halfway point of its own duration
 * -- so easing one of those spends the back half of the animation with nothing visibly happening, which reads
 * as a jump followed by a delay rather than as a slow fade. The one exception is the status line's
 * <em>travel</em>, which really does arrive somewhere, and it takes its curve inside the sample rather than
 * from the ramp -- see {@link #announce}.
 *
 * <h2>The reduced-motion collapse</h2>
 *
 * {@link #none()} is a {@code Motion} with no clock: the cues play nothing, and the transitions apply their end
 * state without travelling to it. That is the honest collapse rather than a fast version, because a cue has no
 * end state to snap to -- "instantly" and "not at all" are the same thing for a sweep. Routing every animation
 * in the application through one of these is what makes it one decision rather than a dozen.
 *
 * <p>Nothing here is used before it is wanted: a {@code Motion} with a clock costs a per-frame effect only
 * while something is actually in flight.
 */
final class Motion {

    /**
     * A change between two states, both of which the eye can look at. The pad swap and the status line.
     */
    static final Dur TRANSITION = Dur.ms(160);

    /**
     * A one-shot mark saying something happened. Longer than a transition because the question it has to
     * answer is not "was it continuous" but "was it noticed".
     *
     * <p>{@code Cue.scanline}'s own note is the reason it is not shorter: the trail is a large fraction of the
     * box, so a cue much under this leaves too little of the sweep visible for anything to register.
     */
    static final Dur CUE = Dur.ms(240);

    /**
     * A refusal. Longer than an acknowledgement, because an acknowledgement only has to be noticed and a
     * refusal is asking to be read -- which is the whole reason {@code Cues.play} takes a ramp of its own.
     */
    static final Dur ALERT = Dur.ms(400);

    /** How far the status line rises as it arrives, in multiples of its own em. */
    private static final float RISE_EM = 0.6f;

    /** Null for a {@link #none()} -- the one field that makes every method here do nothing. */
    private final KronoGui krono;

    /**
     * One-shot marks on this application's nodes. Never null: {@link Cues#none()} where there is no clock,
     * which plays nothing whatever ramp it is handed, so the two travel together and neither has to check the
     * other. {@link Cues#active()} is also the one number that says the class is at rest.
     */
    final Cues cues;

    /** The longer timing {@link #refused} plays its ring on. Null alongside a {@link Cues#none()}. */
    private final Ramp alert;

    private Motion(KronoGui krono) {
        this.krono = krono;
        this.cues = krono == null ? Cues.none()
                : new Cues((progress, done) -> krono.ramp(CUE, Ease.LINEAR, progress, done));
        this.alert = krono == null ? null
                : (progress, done) -> krono.ramp(ALERT, Ease.LINEAR, progress, done);
    }

    /** Motion timed by {@code krono}. */
    static Motion of(KronoGui krono) {
        return new Motion(java.util.Objects.requireNonNull(krono, "krono"));
    }

    /** Motion that does not move. See the class note on why that is the honest collapse and not a fast one. */
    static Motion none() {
        return new Motion(null);
    }

    /** Whether anything here actually animates. */
    boolean enabled() {
        return krono != null;
    }

    /**
     * The timing a widget that animates itself should be given -- the tab panel, today.
     *
     * <p>Null with no clock, which is what {@code Tabs.transition} wants for the instant flip: a widget with no
     * ramp installed does exactly what it did before there was motion, so the reduced-motion path is simply
     * not handing it one.
     */
    Ramp transition() {
        return krono == null ? null
                : (progress, done) -> krono.ramp(TRANSITION, Ease.LINEAR, progress, done);
    }

    // ---- the four things this application has to say -------------------------------------------------

    /**
     * <b>This was evaluated.</b> A bright line sweeping down the entry once.
     *
     * <p>The press that most needs saying so is the one that changes nothing on screen: {@code =} on an
     * expression that reduces to itself, or a second {@code =} on a result already showing. Before this there
     * was no way at all to tell that the key had landed -- the display simply held still, which is
     * indistinguishable from a dropped click.
     */
    void acknowledged(Node entry) {
        cues.play(entry, Cue.scanline(Palette.BTN_BLUE_HOVER));
    }

    /**
     * <b>This was refused.</b> A ring around the entry, pulsed twice, over the longer {@link #ALERT}.
     *
     * <p>Two pulses rather than one because a single flash is the same shape as every other mark here and so
     * says only "something happened"; a repeat is what the eye reads as insistence, and refusal is the case
     * that has to be distinguishable at a glance from acceptance.
     *
     * <p>It rings the <b>entry</b> rather than the status line, because the entry is what the message is
     * about -- and because the entry is deliberately left holding the rejected expression so it can be fixed
     * and re-evaluated. A mark on the thing you are about to correct is a mark in the right place.
     */
    void refused(Node entry) {
        cues.play(entry, Cue.ring(Palette.REFUSED, 2), alert);
    }

    /**
     * <b>This changed, and you did not do it.</b> A wash over the whole box, up fast and down slow.
     *
     * <p>For a line arriving in the entry from the history window or the definitions window -- a different
     * window, which is to say somewhere the eye is not. The attack is short and the release is most of the
     * duration, which is the asymmetry that makes a flash read as a flash rather than as something appearing:
     * the point is to draw the eye to <em>where</em>, not to make it watch something.
     */
    void changed(Node entry) {
        cues.play(entry, Cue.wash(Palette.WASH));
    }

    /**
     * <b>There is something to read here.</b> The status line fades up and rises into place.
     *
     * <p>Before this the line simply had its text replaced, so pressing {@code =} twice on the same bad
     * expression rewrote an identical string and nothing moved -- a report that cannot report itself twice.
     *
     * <p><b>One ramp, two curves, and it has to be.</b> The fade is linear because opacity has nowhere to
     * arrive; the travel is {@code OUT_CUBIC} because it does. Taking the ease inside the sample rather than
     * from the ramp is what lets one ramp drive both, and it is the same division {@code Tabs.slide} makes
     * between its own travel and the fade underneath it.
     *
     * <p>With no clock the end state is applied and nothing travels to it -- so the line is legible in a
     * capture that never ticks, rather than left at opacity zero forever by a ramp that delivered its 0 and
     * then had no frames to finish on.
     */
    void announce(Node status) {
        if (krono == null) {
            status.opacity(1f).translate(0f, 0f);
            return;
        }
        krono.ramp(TRANSITION, Ease.LINEAR,
                t -> {
                    status.opacity((float) t);
                    status.translate(0f, RISE_EM * (1f - Ease.OUT_CUBIC.at((float) t)));
                },
                () -> status.opacity(1f).translate(0f, 0f));
    }
}
