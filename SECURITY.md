# Security Policy

## Reporting a Vulnerability

We take security seriously and appreciate your efforts to responsibly disclose any security vulnerabilities you discover.

### How to Report

**Please do NOT open a public GitHub issue for security vulnerabilities.** Instead, please report security vulnerabilities to the project maintainers privately.

1. **Use GitHub's Security Advisory Form**
   - Navigate to: https://github.com/egdels/makeacopy/security/advisories/new
   - This creates a private report visible only to repository maintainers

2. **Or contact the maintainers directly**
   - Send an email describing the vulnerability with details about:
     - The type of vulnerability
     - Location in the code (if applicable)
     - Potential impact
     - Any suggested fixes or workarounds

### What to Include

Please provide as much information as possible to help us understand and assess the vulnerability:

- **Description** - A clear description of the vulnerability
- **Affected Version(s)** - Which version(s) of MakeACopy are affected
- **Steps to Reproduce** - How to reproduce the issue (if applicable)
- **Potential Impact** - What could an attacker do with this vulnerability?
- **Suggested Fix** - If you have a suggestion for fixing the issue (optional)
- **Your Contact Information** - How we can reach you if we need more details

## Security Response Timeline

We will make our best effort to:

1. **Acknowledge receipt** - Respond within 3-5 business days to confirm we received your report
2. **Investigate** - Work to understand and reproduce the issue
3. **Develop a fix** - Create and test a patch
4. **Coordinate disclosure** - Work with you on a responsible disclosure timeline
5. **Release patch** - Issue a security update and publicly disclose the vulnerability

Depending on the complexity and severity of the vulnerability, this process may take anywhere from a few days to a few weeks.

## Disclosure Policy

We follow a responsible disclosure process:

1. After receiving your report, we will work to verify and understand the vulnerability
2. We will attempt to fix the issue and release a patch
3. We will coordinate with you on the timing of public disclosure
4. We will credit you in the security advisory if you wish (with your permission)
5. We aim to have a patch available before public disclosure, when possible

## Supported Versions

| Version | Status | Security Updates |
|---------|--------|-----------------|
| 3.x     | Latest | ✅ Yes          |
| 2.x     | Older  | ⚠️ Case by case |
| 1.x     | Outdated | ❌ No         |

We recommend users keep their application up to date with the latest version to ensure they have the latest security patches.

## Security Considerations

### Offline-First Architecture

MakeACopy is designed to work completely offline. This design choice significantly reduces certain classes of security risks:

- ✅ No network requests to untrusted servers
- ✅ No cloud storage of user data
- ✅ No tracking or analytics
- ✅ No third-party API dependencies

However, other security considerations remain important:

- Input validation for document processing
- Safe handling of OCR models and image processing
- Secure storage of any cached data
- Safe handling of file I/O operations

### Dependencies

We carefully select and maintain our dependencies:

- **ONNX Runtime** - ML inference engine for document detection
- **PaddleOCR** (models run on ONNX Runtime) - OCR

All dependencies are regularly updated to patch known vulnerabilities. We use tools like:

- Gradle dependency scanning
- GitHub's Dependabot for monitoring CVEs
- Regular security audits

### Code Review

All code changes, including security fixes, go through:

- Code review by maintainers
- Automated testing (unit and instrumented tests)
- Lint checks and code quality analysis
- Compatibility testing on multiple Android versions

### Android Security Features

MakeACopy leverages Android's built-in security features:

- **Sandboxing** - Each app instance is isolated from others
- **Permission System** - Users must grant permissions for camera, storage, etc.
- **SELinux** - Android's mandatory access control framework
- **Code Signing** - All releases are cryptographically signed

### Build Reproducibility

Official releases are built to be reproducible, allowing community members to verify builds:

- Fixed tool versions (JDK, NDK, CMake)
- Deterministic build process
- Published build scripts and documentation
- APK signature verification available

## APK Verification

All official releases are cryptographically signed. You can verify the authenticity of APKs:

**Upload Key (used for GitHub releases, F-Droid, and sideload APKs):**
```
SHA-256: AE:32:2D:3F:B7:1A:FE:21:DF:47:27:E3:7A:5C:68:03:51:1D:5A:2F:E1:FC:31:35:43:0C:EE:06:99:FA:1B:34
```

**Google Play App Signing Key:**
- Used for Play Store releases only

## Staying Informed

To stay informed about security updates:

- ⭐ Watch the GitHub repository for release notifications
- 📧 Subscribe to release announcements
- 🔔 Enable notifications for security advisories

## Scope

This security policy covers:

- The MakeACopy application code
- Official releases (F-Droid, Google Play, GitHub Releases)
- The project documentation and build infrastructure

This security policy does **not** cover:

- Third-party forks or modified versions
- Older versions beyond the supported versions listed above
- Dependencies maintained by third parties (though we will help coordinate fixes)

## Questions or Concerns?

If you have questions about this security policy or security in general, feel free to open a private security advisory or discussion in the repository.

---

**Thank you for helping keep MakeACopy secure!**

We appreciate the security research community and responsible disclosure practices that help make our project safer for all users.

