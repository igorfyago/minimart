package dev.b4rruf3t.sso.client;

/** A validated SSO user from a JWT. */
public record SsoUser(String sub, String name, String email) {}
