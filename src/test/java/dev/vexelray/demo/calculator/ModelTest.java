package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    @DisplayName("a change lands, and listeners see the value that landed")
    void changeLands() {
        Model model = new Model();
        List<Ramp> seen = new ArrayList<>();
        model.onChange(s -> seen.add(s.ramp()));

        assertSame(Ramp.BLURPLE, model.scene().ramp(), "the default");
        model.change(s -> Scene.with(s, w -> w.ramp(Ramp.ASH)));

        assertSame(Ramp.ASH, model.scene().ramp(), "the change did not stick");
        assertEquals(List.of(Ramp.ASH), seen, "the listener saw the wrong value, or none");
    }

    @Test
    @DisplayName("a change is applied to the current value, not to the one the caller read")
    void editsAreRelative() {
        Model model = new Model();
        Scene stale = model.scene();
        model.change(s -> Scene.with(s, w -> w.omega(7)));

        // A caller holding `stale` now changes something else. If edits were absolute -- if this rebuilt the
        // whole scene from what it read -- omega would go back to 4 and the earlier change would vanish with
        // no error anywhere. That is the failure resolved decision 11 is about.
        model.change(s -> Scene.with(s, w -> w.samples(99)));

        assertEquals(7, model.scene().omega(), 1e-9, "an unrelated edit reverted omega");
        assertEquals(99, model.scene().samples());
        assertEquals(4, stale.omega(), 1e-9, "the snapshot the caller held must be immutable");
    }

    @Test
    @DisplayName("the expression and its reading change together, so nothing can describe the other one")
    void submitSettlesBothAtOnce() {
        Model model = new Model();
        List<Scene> seen = new ArrayList<>();
        model.onChange(seen::add);

        model.submit("sin(x)*cos(y)");

        assertEquals(1, seen.size(), "one version, not two");
        assertEquals("sin(x)*cos(y)", seen.getFirst().expression());
        assertSame(Canned.Mode.SURFACE, seen.getFirst().reading().mode());
        for (Scene s : seen) {
            assertSame(Canned.read(s.expression()).mode(), s.reading().mode(),
                    "a version in which the expression and the reading disagree");
        }
    }

    @Test
    @DisplayName("an unrecognised expression is refused out loud rather than drawn as something else")
    void refusalIsLoud() {
        Model model = new Model();
        model.submit("cot(x) + 3");

        assertEquals("cot(x) + 3", model.scene().expression(), "the entry stays as typed");
        assertNotEquals(null, model.scene().reading().refusal(), "no refusal was reported");
        assertTrue(model.scene().reading().refusal().contains("no evaluator"),
                "the refusal should say why: " + model.scene().reading().refusal());
    }

    /**
     * Every panel control's handler runs on the worker executor, so several really can be in flight at once —
     * the CAS in {@code State.commit} is the only thing putting them in an order. This is the test that would
     * fail if a change were ever written as "read, modify, write".
     */
    @Test
    @DisplayName("concurrent edits from many threads all survive")
    void concurrentEditsDoNotLoseEachOther() throws InterruptedException {
        Model model = new Model();
        int threads = 8;
        int each = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    go.await();
                    for (int i = 0; i < each; i++) {
                        model.change(s -> Scene.with(s, w -> w.samples(s.samples() + 1)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        int before = model.scene().samples();
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the edits did not finish");
        pool.shutdown();

        assertEquals(before + threads * each, model.scene().samples(),
                "an increment was lost -- which is exactly what a stale read produces, silently");
    }
}
