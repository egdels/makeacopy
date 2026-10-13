package de.schliweb.makeacopy.utils.infra;

import static org.junit.Assert.*;

import org.junit.Test;

/** The rule for Uris handed to the app from outside (security audit F-01). */
public class IncomingUriPolicyTest {

  private static final String OWN = "de.schliweb.makeacopy";

  @Test
  public void otherAppsContentUri_isAllowed() {
    assertTrue(
        IncomingUriPolicy.isAllowed(
            "content", "com.android.providers.media.documents", "com.android.providers.media", OWN));
    assertTrue(IncomingUriPolicy.isAllowed("content", "media", null, OWN));
    assertTrue(IncomingUriPolicy.isAllowed("CONTENT", "media", null, OWN));
  }

  @Test
  public void fileUri_isRejected() {
    assertFalse(IncomingUriPolicy.isAllowed("file", null, null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("file", "", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("FILE", null, null, OWN));
  }

  @Test
  public void otherSchemesAndMissingParts_areRejected() {
    assertFalse(IncomingUriPolicy.isAllowed(null, "media", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("https", "example.org", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", null, null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", "", null, OWN));
  }

  @Test
  public void ownProviders_areRejected() {
    assertFalse(IncomingUriPolicy.isAllowed("content", OWN + ".fileprovider", OWN, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", OWN + ".fileprovider", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", OWN + ".androidx-startup", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", OWN, null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", "DE.SCHLIWEB.MAKEACOPY.FILEPROVIDER", null, OWN));
    // Resolved to our package under an unexpected name: still ours
    assertFalse(IncomingUriPolicy.isAllowed("content", "renamed", OWN, OWN));
  }

  @Test
  public void userPrefixAndPort_doNotHideOwnProvider() {
    assertFalse(IncomingUriPolicy.isAllowed("content", "0@" + OWN + ".fileprovider", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", "10@" + OWN + ".fileprovider", null, OWN));
    assertFalse(IncomingUriPolicy.isAllowed("content", OWN + ".fileprovider:80", null, OWN));
    assertTrue(IncomingUriPolicy.isAllowed("content", "0@media", null, OWN));
  }

  @Test
  public void similarButForeignPackage_isAllowed() {
    // A prefix of our name without the dot is another app, e.g. a fork with its own id
    assertTrue(IncomingUriPolicy.isAllowed("content", OWN + "fork.provider", null, OWN));
  }

  @Test
  public void siblingEdition_isAllowed() {
    // The Light edition shares into the Full edition: resolved to its own package ...
    assertTrue(
        IncomingUriPolicy.isAllowed("content", OWN + ".light.fileprovider", OWN + ".light", OWN));
    // ... or unresolved, recognisable by the two segments after our id
    assertTrue(IncomingUriPolicy.isAllowed("content", OWN + ".light.fileprovider", null, OWN));
    assertTrue(IncomingUriPolicy.isAllowed("content", "0@" + OWN + ".light.fileprovider", null, OWN));
    // A resolved foreign package wins over a look-alike name
    assertTrue(IncomingUriPolicy.isAllowed("content", OWN + ".fileprovider", "org.example", OWN));
  }

  @Test
  public void providerAuthority_stripsUserAndPort() {
    assertEquals("media", IncomingUriPolicy.providerAuthority("0@media"));
    assertEquals("media", IncomingUriPolicy.providerAuthority("media:443"));
    assertEquals("media", IncomingUriPolicy.providerAuthority("1@media:443"));
    assertNull(IncomingUriPolicy.providerAuthority(null));
  }
}
