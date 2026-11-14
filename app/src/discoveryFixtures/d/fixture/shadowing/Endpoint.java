package fixture.shadowing;

/** Base-first ART loading selects this unanchored implementation. */
public final class Endpoint {
    private Endpoint() {}

    public static String transmit(String input) {
        return "shadow-base:".concat(input);
    }
}
