#!/usr/bin/env python3

import hashlib

def generate_otp_hash(pin, salt, passphrase):
    """Generate OTP hash exactly like Android implementation"""
    # MessageDigest digest = MessageDigest.getInstance("SHA-256");
    # String plainText = pin + saltStr + passphrase;
    # byte[] hash = digest.digest(plainText.getBytes());
    
    plaintext = pin + salt + passphrase
    hash_bytes = hashlib.sha256(plaintext.encode('utf-8')).digest()
    hex_string = hash_bytes.hex().upper()
    
    print(f"Input - PIN: {pin}, Salt: {salt}, Passphrase: {passphrase}")
    print(f"PlainText: {plaintext}")
    print(f"SHA256 Hash: {hex_string}")
    
    return hex_string

if __name__ == "__main__":
    # Test with the values from the logs
    pin = "9067"
    salt = "7e4f274a9a39bd8b3f36ef811d318076"
    passphrase = "test"  # Assuming this is the passphrase
    
    print("=== Testing OTP Hash Generation ===")
    hash_result = generate_otp_hash(pin, salt, passphrase)
    
    print("\n=== Expected from logs ===")
    expected = "1DBFA68BC208F14DEF7D6B9355CA49823E3EAED6B8A32B6E031A7AFB28D4F709"
    print(f"Expected hash: {expected}")
    print(f"Generated hash: {hash_result}")
    print(f"Match: {hash_result == expected}")
    
    # Test with empty passphrase
    print("\n=== Testing with empty passphrase ===")
    hash_empty = generate_otp_hash(pin, salt, "")
    print(f"Empty passphrase hash: {hash_empty}")
    
    # Test with different common passphrases
    print("\n=== Testing with common passphrases ===")
    for pp in ["", "default", "password", "123456", "admin"]:
        hash_test = generate_otp_hash(pin, salt, pp)
        print(f"Passphrase '{pp}': {hash_test}")
        if hash_test == expected:
            print(f"*** MATCH FOUND with passphrase: '{pp}' ***")
