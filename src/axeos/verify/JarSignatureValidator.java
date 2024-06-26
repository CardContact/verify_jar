/*
 * Copyright (c) 2012, Axeos B.V, and contributors
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Axeos designates this
 * particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package axeos.verify;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSigner;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStore.LoadStoreParameter;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.Timestamp;
import java.security.cert.CRLException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import axeos.verify.exceptions.ExpiredException;
import axeos.verify.exceptions.InvalidException;
import axeos.verify.exceptions.NotSignedException;
import axeos.verify.exceptions.NotTrustedException;
import axeos.verify.exceptions.UnsignedEntriesException;
import axeos.verify.exceptions.ValidatorException;

public class JarSignatureValidator {

	private final List<String> crlFileNames = new ArrayList<String>();

	private final Set<String> displayedWarings = new HashSet<String>();

	private final Logger log = Logger.getLogger(JarSignatureValidator.class.getName());

	private String ocspResponderURL;

	private PKIXParameters params;

	private boolean quiet = false;

	private boolean skipCertUsage = false;

	private boolean skipTrustCheck = false;

	private String trustedKeystore;

	private String trustedKeystorePassword;

	private boolean useOCSP;

	private CertPathValidator validator;

	private Date verificationDate;

	public List<String> getCrlFileNames() {
		return crlFileNames;
	}

	public String getOcspResponderURL() {
		return ocspResponderURL;
	}

	public String getTrustedKeystore() {
		return trustedKeystore;
	}

	public Date getVerificationDate() {
		return verificationDate;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void initPathValdiator() throws NoSuchAlgorithmException, KeyStoreException, CertificateException,
			FileNotFoundException, IOException, InvalidAlgorithmParameterException, CRLException, ValidatorException {

		if (skipTrustCheck) {
			log.fine("Certificate path validation skiped.");
			this.validator = null;
			return;
		}

		Security.setProperty("ocsp.enable", useOCSP ? "true" : "false");
		if (ocspResponderURL != null)
			Security.setProperty("ocsp.responderURL", ocspResponderURL);

		CertPathValidator validator = CertPathValidator.getInstance("PKIX");

		KeyStore keystore = loadKeystore();

		try {
			this.params = new PKIXParameters(keystore);
		} catch (InvalidAlgorithmParameterException e) {
			System.err.println("No trust anchors defined");
			System.err.println("Signer certificate not trusted");
			throw new NotTrustedException();
		}

		if (verificationDate != null) {
			log.fine("Using verification date: " + verificationDate);
			this.params.setDate(verificationDate);
		}

		List l = new ArrayList();

		params.setRevocationEnabled(useOCSP || crlFileNames != null && !crlFileNames.isEmpty());

		if (crlFileNames != null) {
			for (String crlFile : crlFileNames) {
				l.addAll(CertificateFactory.getInstance("X.509").generateCRLs(new FileInputStream(crlFile)));
			}
		}

		CollectionCertStoreParameters csParams = new CollectionCertStoreParameters(l);

		CertStore certStore = CertStore.getInstance("Collection", csParams);

		params.addCertStore(certStore);

		this.validator = validator;
	}

	private boolean isCertForCodeSigning(final X509Certificate cert) throws CertificateParsingException {
		List<String> extUsage = cert.getExtendedKeyUsage();
		// 2.5.29.37.0 - Any extended key usage
		// 1.3.6.1.5.5.7.3.3 - Code Signing
		return extUsage != null && (extUsage.contains("2.5.29.37.0") || extUsage.contains("1.3.6.1.5.5.7.3.3"));
	}

	public boolean isQuiet() {
		return quiet;
	}

	private boolean isSignatureRelatedFilename(String filename) {
		String tmp = filename.toUpperCase();
		if (tmp.equals(JarFile.MANIFEST_NAME) || tmp.equals("META-INF/")
				|| (tmp.startsWith("META-INF/SIG-") && tmp.indexOf("/") == tmp.lastIndexOf("/"))) {
			return true;
		}
		if (tmp.startsWith("META-INF/") && (tmp.endsWith(".SF") || tmp.endsWith(".DSA") || tmp.endsWith(".RSA"))) {
			return (tmp.indexOf("/") == tmp.lastIndexOf("/"));
		}

		return false;
	}

	public boolean isSkipCertUsage() {
		return skipCertUsage;
	}

	public boolean isSkipTrustCheck() {
		return skipTrustCheck;
	}

	public boolean isUseOCSP() {
		return useOCSP;
	}

	private KeyStore loadKeystore() throws KeyStoreException, NoSuchAlgorithmException, CertificateException,
			FileNotFoundException, IOException {
		final File tuststore = new File(System.getProperty("java.home")
				+ "/lib/security/cacerts".replace('/', File.separatorChar));
		final File userStore = new File("~/.keystore");

		KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());

		if (trustedKeystore != null) {
			File f = new File(trustedKeystore);
			if (!f.exists()) {
				showErr("Keystore '" + f + "' does not exists!");
				System.exit(4);
			}
			log.fine("Using keystore: " + f);
			char[] password = null;
			if (this.trustedKeystorePassword != null) {
				password = this.trustedKeystorePassword.toCharArray();
			}
			keystore.load(new FileInputStream(f), password);
		} else if (userStore.exists()) {
			log.fine("Using keystore: " + userStore);
			keystore.load(new FileInputStream(userStore), null);
		} else if (tuststore.exists()) {
			log.fine("Using keystore: " + tuststore);
			keystore.load(new FileInputStream(tuststore), null);
		} else {
			LoadStoreParameter p = null;
			keystore.load(p);
		}

		return keystore;
	}

	public void setOcspResponderURL(String ocspResponderURL) {
		this.ocspResponderURL = ocspResponderURL;
	}

	public void setQuiet(boolean quiet) {
		this.quiet = quiet;
	}

	public void setSkipCertUsage(boolean skipCertUsage) {
		this.skipCertUsage = skipCertUsage;
	}

	public void setSkipTrustCheck(boolean b) {
		this.skipTrustCheck = b;
	}

	public void setTrustedKeystore(String trustedKeystore) {
		this.trustedKeystore = trustedKeystore;
	}

	public void setTrustedKeystorePassword(String trustedKeystorePassword) {
		this.trustedKeystorePassword = trustedKeystorePassword;
	}

	public void setUseOCSP(boolean useOCSP) {
		this.useOCSP = useOCSP;
	}

	public void setVerificationDate(Date verificationDate) {
		this.verificationDate = verificationDate;
	}

	private void showErr(String t) {
		if (!displayedWarings.contains(t)) {
			displayedWarings.add(t);
			System.err.println(t);
		}
	}

	private void validatePath(CertPath path) throws Exception {

		if (validator == null) {
			log.finest("  path validation skiped (it needs trusted keystore)");
			return;
		}

		PKIXCertPathValidatorResult result = (PKIXCertPathValidatorResult) validator.validate(path, params);
		if (result == null)
			throw new RuntimeException("No result???");

		try {
			if (params.getDate() == null) {
				result.getTrustAnchor().getTrustedCert().checkValidity();
			} else {
				result.getTrustAnchor().getTrustedCert().checkValidity(params.getDate());
			}
		} catch (Exception e) {
			if (e instanceof CertificateExpiredException || e.getCause() instanceof CertificateExpiredException) {
				showErr("Trust anchor expired");
			}
			throw new ExpiredException();
		}

		if (log.isLoggable(Level.FINEST)) {
			log.finest("  path valid");
		}
	}

	public void verifyJar(final JarFile jarFile) throws IOException, KeyStoreException, CertificateException,
			NoSuchAlgorithmException, InvalidAlgorithmParameterException, CertPathValidatorException, CRLException,
			ValidatorException {
		displayedWarings.clear();
		byte[] buffer = new byte[8192];

		boolean anySigned = false;
		boolean hasUnsignedEntry = false;

		initPathValdiator();
		// -------

		// -------

		final Manifest manifest = jarFile.getManifest();

		Enumeration<JarEntry> entriesEnum = jarFile.entries();
		while (entriesEnum.hasMoreElements()) {
			JarEntry entry = entriesEnum.nextElement();
			InputStream is = null;

			if (log.isLoggable(Level.FINEST))
				log.finest("Checking file " + entry);
			try {
				is = jarFile.getInputStream(entry);
				// Checking SHA-1
				while ((is.read(buffer, 0, buffer.length)) != -1)
					;
			} catch (java.lang.SecurityException e) {
				if (log.isLoggable(Level.FINEST))
					log.log(Level.FINEST, "  Invalid signature!!!", e);
				throw new InvalidException();
			} finally {
				if (is != null) {
					is.close();
				}
			}

			String name = entry.getName();
			CodeSigner[] codeSigners = entry.getCodeSigners();

			boolean isSigned = (codeSigners != null);
			boolean inManifest = ((manifest.getAttributes(name) != null) || (manifest.getAttributes("./" + name) != null) || (manifest.getAttributes("/"
					+ name) != null));
			anySigned |= isSigned;
			hasUnsignedEntry |= !entry.isDirectory() && !isSigned && !isSignatureRelatedFilename(name);

			if (log.isLoggable(Level.FINEST)) {
				log.finest("  " + (isSigned ? "signed" : "      ") + "  " + (inManifest ? "manifest" : "        ") + "  ");
			}

			if (isSigned) {
				for (int i = 0; i < codeSigners.length; i++) {
					Certificate cert = codeSigners[i].getSignerCertPath().getCertificates().get(0);
					Timestamp timestamp = codeSigners[i].getTimestamp();
					if (timestamp != null) {
						log.finer("  Found timestamp.");
						CertPath cp = timestamp.getSignerCertPath();
						try {
							log.finer("  Validating timestamp certificate path");
							validatePath(cp);
							params.setDate(timestamp.getTimestamp());
						} catch (Exception e) {
							if (e instanceof CertificateExpiredException || e.getCause() instanceof CertificateExpiredException) {
								showErr("Time stamping authority certificate expired");
							} else if ("Path does not chain with any of the trust anchors".equals(e.getMessage())) {
								showErr("Time stamping authority certificate not trusted");
							} else
								showErr("Timestamp: " + e.getMessage());
							log.log(Level.FINE, "Timestamp certificate is not valid", e);
						}

					}

					List<Certificate> x = new ArrayList<Certificate>();
					for (Certificate c : codeSigners[i].getSignerCertPath().getCertificates()) {
						x.add(c);
					}
					CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(x);

					if (cert instanceof X509Certificate) {

						if (log.isLoggable(Level.FINEST)) {
							log.finest("  Used certificate  SerialNumber: " + ((X509Certificate) cert).getSerialNumber()
									+ "; Subject: " + ((X509Certificate) cert).getSubjectDN());
						}
						boolean correctUsage = isCertForCodeSigning((X509Certificate) cert);// TODO
						if (!correctUsage)
							showErr("Wrong key usage");

						if (!skipCertUsage && !correctUsage) {
							log.fine("Certificate can't be used to signing code");
							throw new InvalidException();
						}

						if (log.isLoggable(Level.FINEST)) {
							log.finest("  usage: " + (correctUsage ? "correct" : "incorrect") + ";");
						}
					}

					try {
						log.finest("Validating signer certificate path");
						validatePath(path);
					} catch (ValidatorException e) {
						throw e;
					} catch (Exception e) {
						// e.printStackTrace();
						if ("Path does not chain with any of the trust anchors".equals(e.getMessage())) {
							showErr("Signer certificate not trusted");
						}
						// else
						// showErr(e.getMessage());

						if (e instanceof CertificateExpiredException) {
							throw new ExpiredException();
						} else if (e.getCause() instanceof CertificateExpiredException) {
							throw new ExpiredException();
						}

						log.log(Level.FINE, "Certificate path can't be verified!", e);
						throw new NotTrustedException();
					}
				}
			}

		}

		if (!anySigned) {
			if (log.isLoggable(Level.FINE))
				log.fine("File is not signed");
			throw new NotSignedException();
		} else if (hasUnsignedEntry) {
			showErr("Contains unsigned entries");
			if (log.isLoggable(Level.FINE))
				log.fine("File contains unsigned entries!");
			throw new UnsignedEntriesException();
		}

		if (log.isLoggable(Level.FINE))
			log.fine("File verified");
	}

}
