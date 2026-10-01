# Security Policy

PdfToolkit is an offline Android app maintained by one person in their spare time. There is no
dedicated security team and no SLA, but reports are taken seriously and looked at as soon as
possible.

## Reporting a vulnerability

Please **do not** open a public issue for security vulnerabilities.

Instead, use GitHub's private reporting flow:
[Report a vulnerability](../../security/advisories/new) (Security tab → "Report a vulnerability").
This opens a private advisory visible only to the maintainer until a fix is ready.

If you can't use that flow, open a regular issue asking for an alternative contact without
including any vulnerability details.

## Scope

Things worth reporting: anything that could leak documents or saved signatures to other apps,
crashes or code execution triggered by a crafted PDF or image opened in the app, and any network
access (the app is not supposed to have any).

Things generally out of scope: issues that require a rooted or otherwise compromised device.

## Supported versions

Only the latest published release is supported; older releases don't receive backported fixes.
See the [Releases](../../releases) page for the current version.
