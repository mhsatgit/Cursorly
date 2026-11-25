# Security Policy

## Reporting Security Issues

We take the security of Cursorly seriously. If you believe you have discovered a vulnerability, please report it responsibly:

1. **Do not open a public issue.**
2. Report the vulnerability privately via **GitHub Security Advisories** (**Security -> Advisories -> Report a vulnerability** on `https://github.com/mhsatgit/Cursorly`) with details, reproduction steps, and potential impact.
3. Allow the maintainers reasonable time to investigate and address the vulnerability before public disclosure.

## Security Scope Note

Cursorly listens on local network interfaces (HTTP/WebSocket port `8080`, UDP discovery port `41234`, and Bluetooth RFCOMM) for touch and keyboard input injection. Only run the host service on trusted local networks, USB tethering links, or paired Bluetooth devices.

Thank you for helping keep Cursorly safe for everyone!
