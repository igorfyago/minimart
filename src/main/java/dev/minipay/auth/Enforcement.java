package dev.minipay.auth;

/**
 * THE ACTIVATION SWITCH · one place to answer "does a credential matter yet".
 *
 * Identity landed wired but toothless on purpose. The estate's demos and the
 * seeded NPC traffic carry no credentials, and a processor that starts
 * refusing them on the day the seam ships has not shipped security, it has
 * shipped an outage. So activation is a deployment decision, made once, read
 * everywhere, and OFF until somebody says otherwise.
 *
 * OFF is the behaviour that predates identity: a caller with no credential is
 * anonymous, and the body's word stands. ON means a money-moving endpoint
 * wants a caller who can prove something, and one who cannot is told so.
 *
 * WHAT DOES NOT DEPEND ON THIS SWITCH: a credential that IS presented is
 * always checked. A caller who bothers to prove something has asked to be
 * held to it, and honouring a key while ignoring whether it is real would be
 * the worst of both phases.
 *
 * The system property is here because a test needs to flip the switch inside
 * one JVM, and an environment variable cannot be flipped in a running
 * process. The environment variable is how a deployment sets it.
 */
public final class Enforcement {

    public static final String PROPERTY = "pay.identity.enforce";
    public static final String ENV = "PAY_IDENTITY_ENFORCE";

    private Enforcement() {}

    /** True when a money-moving request without a valid credential is refused. */
    public static boolean on() {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) v = System.getenv(ENV);
        if (v == null) return false;
        String s = v.trim();
        return s.equals("1") || s.equalsIgnoreCase("true");
    }
}
