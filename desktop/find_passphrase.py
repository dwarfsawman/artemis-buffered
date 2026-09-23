#!/usr/bin/env python3

import hashlib
import string
import itertools

def generate_otp_hash(pin, salt, passphrase):
    """Generate OTP hash exactly like Android implementation"""
    plaintext = pin + salt + passphrase
    hash_bytes = hashlib.sha256(plaintext.encode('utf-8')).digest()
    hex_string = hash_bytes.hex().upper()
    return hex_string

def find_passphrase():
    pin = "9067"
    salt = "7e4f274a9a39bd8b3f36ef811d318076"
    expected = "1DBFA68BC208F14DEF7D6B9355CA49823E3EAED6B8A32B6E031A7AFB28D4F709"
    
    print(f"Looking for passphrase that generates hash: {expected}")
    print(f"Using PIN: {pin}, Salt: {salt}")
    
    # Test common passphrases
    common_passphrases = [
        "", "default", "password", "123456", "admin", "test", "user", "guest",
        "apollo", "artemis", "moonlight", "sunshine", "nvidia", "gamestream",
        "pass", "secret", "key", "auth", "otp", "pin", "1234", "0000",
        "server", "client", "pairing", "cert", "ssl", "tls", "https"
    ]
    
    print("\n=== Testing common passphrases ===")
    for passphrase in common_passphrases:
        hash_result = generate_otp_hash(pin, salt, passphrase)
        print(f"Passphrase '{passphrase}': {hash_result}")
        if hash_result == expected:
            print(f"*** MATCH FOUND! Passphrase: '{passphrase}' ***")
            return passphrase
    
    # Test short combinations
    print("\n=== Testing short combinations ===")
    chars = string.ascii_lowercase + string.digits
    for length in range(1, 6):  # Test 1-5 character passphrases
        for combination in itertools.product(chars, repeat=length):
            passphrase = ''.join(combination)
            hash_result = generate_otp_hash(pin, salt, passphrase)
            if hash_result == expected:
                print(f"*** MATCH FOUND! Passphrase: '{passphrase}' ***")
                return passphrase
            
            # Show progress for longer searches
            if length > 3 and passphrase.endswith('aaa'):
                print(f"Testing {length}-char combinations: {passphrase}...")
    
    print("No matching passphrase found in common combinations.")
    return None

if __name__ == "__main__":
    find_passphrase()
