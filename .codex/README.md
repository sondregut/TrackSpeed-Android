# RevenueCat MCP configuration

This project uses Codex's supported `bearer_token_env_var` option. Authentication
reads `TRACKSPEED_REVENUECAT_API_V2_SECRET_KEY` from the process environment that
launches Codex. The repository intentionally contains no bearer token.

Only configure a new or existing key after the owner approves it. Obtain it from
the owner's private credential store or RevenueCat project settings and supply it
outside the repository. Do not place it in a command argument, tracked file,
committed `.env`, screenshot, log, or chat. Codex does not automatically source a
project `.env` file. Without the variable, authenticated RevenueCat MCP operations
are unavailable. No replacement key is created or configured by this change.

This affects developer MCP access only. The Android Purchases SDK has its own
public SDK configuration; this variable is not used by the app, Gradle build, CI
or APK. Revocation may still affect other developer or backend consumers of the
same secret; the owner must identify those before any credential change.

Removing the literal from the current file does not remove it from prior public
Git history or revoke copies. Coordinate owner-approved credential containment
and any history cleanup separately; do not rewrite branches or change repository
visibility as part of this configuration-only change.

References: [Codex MCP configuration](https://developers.openai.com/codex/mcp/)
and [RevenueCat MCP authentication](https://www.revenuecat.com/docs/tools/mcp/setup).
