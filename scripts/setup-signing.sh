#!/bin/zsh
# Creates a self-signed code-signing certificate in the login keychain (once).
#
# Why: an ad-hoc signed app gets a new identity on every build, so macOS forgets its
# Accessibility permission. Signing every build with the same certificate keeps it.
set -euo pipefail

NAME="Persian Dictation Local Signing"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# macOS only honours the Accessibility grant if it can trust the signing certificate.
# This trusts it for code signing only (asks for your password).
trust_certificate() {
  security find-certificate -c "$NAME" -p > "$TMP/trust.pem"
  if security verify-cert -c "$TMP/trust.pem" -p codeSign >/dev/null 2>&1; then
    echo "Certificate is already trusted for code signing."
  else
    security add-trusted-cert -r trustRoot -p codeSign \
      -k "$HOME/Library/Keychains/login.keychain-db" "$TMP/trust.pem"
    echo "Certificate trusted for code signing."
  fi
}

if security find-certificate -c "$NAME" >/dev/null 2>&1; then
  echo "Certificate \"$NAME\" already exists."
  trust_certificate
  exit 0
fi

cat > "$TMP/cert.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
EOF

/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -config "$TMP/cert.cnf" 2>/dev/null
/usr/bin/openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
  -out "$TMP/$NAME.p12" -passout pass:temp -name "$NAME"

# -T pre-authorizes codesign, but recent macOS still asks once per use; answer the first
# keychain prompt with "Always Allow" to stop it.
security import "$TMP/$NAME.p12" -k "$HOME/Library/Keychains/login.keychain-db" \
  -P temp -T /usr/bin/codesign

echo "Created certificate \"$NAME\"."
trust_certificate
