#include "otppairingmanager.h"
#include "nvcomputer.h"
#include <QCoreApplication>
#include <QTimer>
#include <QDebug>

// Simple test to verify OTP pairing integration
int main(int argc, char *argv[])
{
    QCoreApplication app(argc, argv);
    
    // Create OTP pairing manager
    OTPPairingManager otpManager;
    
    // Connect to signals
    QObject::connect(&otpManager, &OTPPairingManager::pairingStarted, []() {
        qDebug() << "Test: OTP pairing started";
    });
    
    QObject::connect(&otpManager, &OTPPairingManager::pairingProgress, [](const QString &status) {
        qDebug() << "Test: OTP pairing progress:" << status;
    });
    
    QObject::connect(&otpManager, &OTPPairingManager::pairingCompleted, [](bool success, const QString &message) {
        qDebug() << "Test: OTP pairing completed:" << success << message;
        QCoreApplication::quit();
    });
    
    QObject::connect(&otpManager, &OTPPairingManager::pairingFailed, [](const QString &error) {
        qDebug() << "Test: OTP pairing failed:" << error;
        QCoreApplication::quit();
    });
    
    // Create a mock computer (this would normally be a real NvComputer object)
    NvComputer *mockComputer = new NvComputer();
    
    // Test the OTP pairing flow
    QTimer::singleShot(100, [&otpManager, mockComputer]() {
        qDebug() << "Test: Starting OTP pairing test...";
        otpManager.startOTPPairing(mockComputer, "1234", "test_passphrase");
    });
    
    return app.exec();
}
