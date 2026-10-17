package dev.vexelray.demo.calculator;

import dev.vexelray.gui.typeset.Box;
import dev.vexelray.gui.typeset.Profile;
import dev.vexelray.gui.typeset.Recipes;
import sibarum.cott.algebra.Pair;
import sibarum.cott.algebra.TractionAlgebra;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * An engine value written as an expression, for the typesetter: each pair as what it stands for, so
 * {@code S(Z(6, 1), S(9, Z(1, 2)))} is {@code 6·0 + 9 + 0²} and {@code Q(1, 2)} a stacked fraction. A line that
 * wrote a constructor is answered in constructors instead, as its text is, with the coordinates typeset.
 *
 * <p>The writing is over a {@link Make}, so the same rules build a {@link Box} for the tape and a plain string for a
 * test to read.
 *
 * <table>
 *   <tr><td>{@code Z(p, q)}</td><td>{@code p·0^q}, or {@code p·ω^n} where {@code q = −n}; a coefficient 1 is left
 *   out, so {@code 0²} and {@code ω}</td></tr>
 *   <tr><td>{@code S(p, q)}</td><td>{@code p + q}, nested sums flattened</td></tr>
 *   <tr><td>{@code D(p, q)}</td><td>{@code q − p}</td></tr>
 *   <tr><td>{@code Q(p, q)}</td><td>{@code p} over {@code q}, or {@code p} alone where {@code q} is 1</td></tr>
 *   <tr><td>{@code C(p, q)}</td><td>{@code q + p·i}</td></tr>
 *   <tr><td>{@code P(p, q)}</td><td>{@code p · q}</td></tr>
 *   <tr><td>{@code E(p, q)}</td><td>{@code q + log₀ p}</td></tr>
 *   <tr><td>{@code B(p, q)}</td><td>{@code log_p q}</td></tr>
 * </table>
 *
 * A term that is the number {@code 0} is left out of a sum, as the typeless identities inside {@code S(0, x)} are.
 */
final class Expressions {

    /** What an expression is built out of. */
    interface Make<B> {
        B number(String text);

        /** An upright named constant: {@code ω}, {@code i}. */
        B constant(String text);

        B operator(String text);

        /** A sign in front of a term: unary, so spaced as a number rather than as an operator. */
        B sign(String text);

        B function(String text);

        B punctuation(String text);

        B fraction(B numerator, B denominator);

        B power(B base, B exponent);

        B subscript(B base, B subscript);

        B fenced(B content);

        /** A constructor's argument list: parentheses set close to its name, as a function call's are. */
        B arguments(B content);

        B row(List<B> items);
    }

    /** The typesetter's boxes, in {@code profile}. */
    static Make<Box> boxes(Profile profile) {
        return new Make<>() {
            public Box number(String text) {
                return Recipes.number(text);
            }

            public Box constant(String text) {
                return Recipes.number(text);
            }

            public Box operator(String text) {
                return Recipes.operator(text);
            }

            public Box sign(String text) {
                return Recipes.number(text);
            }

            public Box function(String text) {
                return Recipes.function(text);
            }

            public Box punctuation(String text) {
                return Recipes.punctuation(text);
            }

            public Box fraction(Box numerator, Box denominator) {
                return Recipes.fraction(profile, numerator, denominator);
            }

            public Box power(Box base, Box exponent) {
                return Recipes.script(profile, base, exponent, null);
            }

            public Box subscript(Box base, Box subscript) {
                return Recipes.script(profile, base, null, subscript);
            }

            public Box fenced(Box content) {
                return Recipes.fenced(profile, "(", ")", content);
            }

            public Box arguments(Box content) {
                return Box.row(Box.run("(", Profile.FACE_MATH, Profile.MathClass.OPEN), content,
                        Box.run(")", Profile.FACE_MATH, Profile.MathClass.CLOSE));
            }

            public Box row(List<Box> items) {
                return Box.row(items);
            }
        };
    }

    /** A plain linear writing, for tests: a fraction is {@code {p}/{q}}, a power {@code b^{e}}, a subscript {@code b_{s}}. */
    static final Make<String> TEXT = new Make<>() {
        public String number(String text) {
            return text;
        }

        public String constant(String text) {
            return text;
        }

        public String operator(String text) {
            return text.equals("·") ? text : " " + text + " ";
        }

        public String sign(String text) {
            return text;
        }

        public String function(String text) {
            return text;
        }

        public String punctuation(String text) {
            return text + " ";
        }

        public String fraction(String numerator, String denominator) {
            return "{" + numerator + "}/{" + denominator + "}";
        }

        public String power(String base, String exponent) {
            return base + "^{" + exponent + "}";
        }

        public String subscript(String base, String subscript) {
            return base + "_{" + subscript + "}";
        }

        public String fenced(String content) {
            return "(" + content + ")";
        }

        public String arguments(String content) {
            return "(" + content + ")";
        }

        public String row(List<String> items) {
            return String.join("", items);
        }
    };

    /** How tightly a writing binds: a sum must be fenced to be a factor, a product to be a base. */
    private enum Binding {
        SUM, PRODUCT, ATOM
    }

    private record Written<B>(B out, Binding binding) {
    }

    /** One term of a sum, its sign apart from it. */
    private record Term<B>(boolean negative, Written<B> magnitude) {
    }

    /** A value with its sign taken off, where its algebra carries one. */
    private record Signed(boolean negative, Object magnitude) {
    }

    private Expressions() {
    }

    /** {@code value} written by meaning, or in constructors where its line wrote one. */
    static <B> B of(Object value, boolean constructed, Make<B> make) {
        return constructed ? constructors(value, make) : meaning(value, make).out();
    }

    // ---- by constructor ----

    private static <B> B constructors(Object v, Make<B> make) {
        if (!(v instanceof Pair<?> pair)) return make.number(text(v));
        return make.row(List.of(make.constant(pair.algebra().name()), make.arguments(make.row(List.of(
                constructors(pair.p(), make), make.punctuation(","), constructors(pair.q(), make))))));
    }

    // ---- by meaning ----

    /** Any value: the sum of its terms, which is one term, signed, for anything but a sum. */
    private static <B> Written<B> meaning(Object v, Make<B> make) {
        return sum(terms(v, false, make), make);
    }

    private static <B> Written<B> sum(List<Term<B>> terms, Make<B> make) {
        if (terms.isEmpty()) return new Written<>(make.number("0"), Binding.ATOM);
        if (terms.size() == 1 && !terms.getFirst().negative()) return terms.getFirst().magnitude();
        List<B> items = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            Term<B> t = terms.get(i);
            if (i > 0) items.add(make.operator(t.negative() ? "−" : "+"));
            else if (t.negative()) items.add(make.sign("−"));
            items.add(i == 0 && t.negative() ? factor(t.magnitude(), make) : t.magnitude().out());
        }
        return new Written<>(make.row(items), Binding.SUM);
    }

    /** The terms of {@code v}, each negated where {@code negate} says: a sum's flattened, any other value one term. */
    private static <B> List<Term<B>> terms(Object v, boolean negate, Make<B> make) {
        List<Term<B>> out = new ArrayList<>();
        if (v instanceof Pair<?> pair) {
            switch (pair.algebra()) {
                case S -> {
                    out.addAll(terms(pair.p(), negate, make));
                    out.addAll(terms(pair.q(), negate, make));
                    return out;
                }
                case D -> {
                    out.addAll(terms(pair.q(), negate, make));
                    out.addAll(terms(pair.p(), !negate, make));
                    return out;
                }
                case C -> {
                    out.addAll(terms(pair.q(), negate, make));
                    if (!zero(pair.p())) {
                        Signed s = signed(pair.p());
                        out.add(new Term<>(negate != s.negative(), imaginary(s.magnitude(), make)));
                    }
                    return out;
                }
                case E -> {
                    out.addAll(terms(pair.q(), negate, make));
                    B log = make.subscript(make.function("log"), make.number("0"));
                    out.add(new Term<>(negate, new Written<>(
                            make.row(List.of(log, factor(meaning(pair.p(), make), make))), Binding.PRODUCT)));
                    return out;
                }
                default -> {
                }
            }
        } else if (zero(v)) {
            return out;
        }
        Signed s = signed(v);
        out.add(new Term<>(negate != s.negative(), magnitude(s.magnitude(), make)));
        return out;
    }

    /** A value with no sign of its own in front: a number, a fraction, a product, a power of zero. */
    private static <B> Written<B> magnitude(Object v, Make<B> make) {
        if (!(v instanceof Pair<?> pair)) return new Written<>(make.number(text(v)), Binding.ATOM);
        return switch (pair.algebra()) {
            case Q -> one(pair.q()) ? meaning(pair.p(), make)
                    : new Written<>(make.fraction(meaning(pair.p(), make).out(), meaning(pair.q(), make).out()),
                    Binding.ATOM);
            case Z -> zeroPower(pair, make);
            case P -> new Written<>(make.row(List.of(factor(meaning(pair.p(), make), make), make.operator("·"),
                    factor(meaning(pair.q(), make), make))), Binding.PRODUCT);
            case B -> new Written<>(make.row(List.of(make.subscript(make.function("log"),
                    meaning(pair.p(), make).out()), factor(meaning(pair.q(), make), make))), Binding.PRODUCT);
            case S, D, C, E -> meaning(v, make);
        };
    }

    /** {@code p·0^q}: the coefficient left out where it is 1, and {@code ω^n} where the order is negative. */
    private static <B> Written<B> zeroPower(Pair<?> z, Make<B> make) {
        BigInteger order = whole(z.q());
        if (order == null) return new Written<>(constructors(z, make), Binding.ATOM);
        if (order.signum() == 0) return meaning(z.p(), make);
        B base = make.constant(order.signum() > 0 ? "0" : "ω");
        BigInteger n = order.abs();
        B power = n.equals(BigInteger.ONE) ? base : make.power(base, make.number(n.toString()));
        if (one(z.p())) return new Written<>(power, Binding.ATOM);
        return new Written<>(make.row(List.of(factor(meaning(z.p(), make), make), make.operator("·"), power)),
                Binding.PRODUCT);
    }

    /** {@code p·i}, or {@code i} where {@code p} is 1. */
    private static <B> Written<B> imaginary(Object p, Make<B> make) {
        B i = make.constant("i");
        if (one(p)) return new Written<>(i, Binding.ATOM);
        return new Written<>(make.row(List.of(factor(meaning(p, make), make), i)), Binding.PRODUCT);
    }

    /** A writing as a factor of a product: fenced where it is a sum. */
    private static <B> B factor(Written<B> w, Make<B> make) {
        return w.binding() == Binding.SUM ? make.fenced(w.out()) : w.out();
    }

    // ---- numbers ----

    /** The sign taken off a number, or off the {@code p} of a {@code Q}, {@code P} or {@code Z}, which carries it. */
    private static Signed signed(Object v) {
        if (v instanceof Pair<?> pair) {
            TractionAlgebra a = pair.algebra();
            if (a == TractionAlgebra.Q || a == TractionAlgebra.P || a == TractionAlgebra.Z) {
                Signed p = signed(pair.p());
                return p.negative() ? new Signed(true, new Pair<>(a, p.magnitude(), pair.q())) : new Signed(false, v);
            }
            return new Signed(false, v);
        }
        return switch (v) {
            case BigInteger n when n.signum() < 0 -> new Signed(true, n.negate());
            case BigDecimal d when d.signum() < 0 -> new Signed(true, d.negate());
            case Double d when d < 0 -> new Signed(true, -d);
            default -> new Signed(false, v);
        };
    }

    private static boolean zero(Object v) {
        return switch (v) {
            case BigInteger n -> n.signum() == 0;
            case BigDecimal d -> d.signum() == 0;
            case Double d -> d == 0;
            default -> false;
        };
    }

    /** The number 1, or a {@code Q} of 1 over 1, which is written as 1: a coefficient of it is left out. */
    private static boolean one(Object v) {
        return switch (v) {
            case BigInteger n -> n.equals(BigInteger.ONE);
            case BigDecimal d -> d.compareTo(BigDecimal.ONE) == 0;
            case Double d -> d == 1;
            case Pair<?> pair -> pair.algebra() == TractionAlgebra.Q && one(pair.p()) && one(pair.q());
            default -> false;
        };
    }

    private static BigInteger whole(Object v) {
        try {
            return switch (v) {
                case BigInteger n -> n;
                case BigDecimal d -> d.toBigIntegerExact();
                case Double d when d == Math.rint(d) && !d.isInfinite() -> BigDecimal.valueOf(d).toBigIntegerExact();
                default -> null;
            };
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }

    /** A number as it is written: {@code −} for its sign, {@code ∞} for a double's infinity. */
    private static String text(Object v) {
        return switch (v) {
            case BigInteger n -> n.toString().replace('-', '−');
            case BigDecimal d -> d.toPlainString().replace('-', '−');
            case Double d when d.isNaN() -> "NaN";
            case Double d when d.isInfinite() -> d > 0 ? "∞" : "−∞";
            case Double d -> BigDecimal.valueOf(d).stripTrailingZeros().toPlainString().replace('-', '−');
            default -> String.valueOf(v);
        };
    }
}
