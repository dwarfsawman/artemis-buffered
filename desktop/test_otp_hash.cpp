#include <QCoreApplication>
#include <QDebug>
#include <QCryptographicHash>
#include <QString>

QString generateOTPHash(const QString &pin, const QString &salt, const QString &passphrase)
{
    // Matches Android implementation:
    // MessageDigest digest = MessageDigest.getInstance("SHA-256");
    // String plainText = pin + saltStr + passphrase;
    // byte[] hash = digest.digest(plainText.getBytes());
    
    QString plainText = pin + salt + passphrase;
    
    QCryptographicHash hash(QCryptographicHash::Sha256);
    hash.addData(plainText.toUtf8());
    
    QByteArray result = hash.result();
    
    // Convert to hex string (uppercase to match Android)
    QString hexString = result.toHex().toUpper();
    
    qDebug() << "Input - PIN:" << pin << "Salt:" << salt << "Passphrase:" << passphrase;
    qDebug() << "PlainText:" << plainText;
    qDebug() << "SHA256 Hash:" << hexString;
    
    return hexString;
}

int main(int argc, char *argv[])
{
    QCoreApplication app(argc, argv);
    
    // Test with the values from the logs
    QString pin = "9067";
    QString salt = "7e4f274a9a39bd8b3f36ef811d318076";
    QString passphrase = "test";  // Assuming this is the passphrase
    
    qDebug() << "=== Testing OTP Hash Generation ===";
    QString hash = generateOTPHash(pin, salt, passphrase);
    
    qDebug() << "\n=== Expected from logs ===";
    qDebug() << "Expected hash: 1DBFA68BC208F14DEF7D6B9355CA49823E3EAED6B8A32B6E031A7AFB28D4F709";
    qDebug() << "Generated hash:" << hash;
    qDebug() << "Match:" << (hash == "1DBFA68BC208F14DEF7D6B9355CA49823E3EAED6B8A32B6E031A7AFB28D4F709");
    
    return 0;
}
