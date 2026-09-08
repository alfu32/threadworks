# User Login

## Scope

The desktop application provides a shared user identity independently of any
project document. A user may sign in with Google, GitHub, or Microsoft OAuth.
Threadwork reads only the account email address, username, full name, and
profile photo needed to identify model changes.

## OAuth Configuration

Desktop login uses the authorization-code flow with a loopback callback and
PKCE. Provider application registrations supply client identifiers through
environment variables or equivalent JVM properties:

| Provider | Environment | JVM property |
| --- | --- | --- |
| Google | `THREADWORK_GOOGLE_CLIENT_ID` | `threadwork.oauth.google.clientId` |
| GitHub | `THREADWORK_GITHUB_CLIENT_ID` | `threadwork.oauth.github.clientId` |
| Microsoft | `THREADWORK_MICROSOFT_CLIENT_ID` | `threadwork.oauth.microsoft.clientId` |

GitHub's token exchange also requires `THREADWORK_GITHUB_CLIENT_SECRET` or
`threadwork.oauth.github.clientSecret`. Google may receive an optional secret
through the corresponding `GOOGLE` setting. Tokens are held only for the
profile request and are not persisted.

## Persistence And Expiration

Profile fields and their expiration timestamp are stored in the user-level
Java Preferences node `com/threadwork/app/identity`, making the identity
available to all Threadwork processes owned by that operating-system user.
The cached profile expires seven days after authentication. Expired details
and the cached avatar image are deleted before the system-user fallback is
used.

The normalized profile image is cached under
`~/.threadwork/identity-avatar.png`. No access token or refresh token is stored.

## Designator And Avatar

The displayed and audit user designator uses the first available value:

1. username
2. full name
3. email address
4. current operating-system user

The title bar places the designator immediately left of a circular profile
medallion at its extreme right. When no profile image exists, the medallion
uses initials from the designator. Its border and initials use the RGB color
formed by the first six hexadecimal digits of the designator's MD5 digest;
the circle uses a contrasting black or white fill.
