package io.stormbird.wallet.service;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.security.keystore.*;
import android.util.Log;
import android.widget.Toast;
import io.stormbird.wallet.R;
import io.stormbird.wallet.entity.*;
import io.stormbird.wallet.widget.AWalletAlertDialog;
import io.stormbird.wallet.widget.SignTransactionDialog;
import wallet.core.jni.PrivateKey;
import wallet.core.jni.*;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import java.io.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.time.ZoneOffset;
import java.util.*;

import static android.os.VibrationEffect.DEFAULT_AMPLITUDE;
import static io.stormbird.wallet.entity.tokenscript.TokenscriptFunction.ZERO_ADDRESS;

@TargetApi(23)
public class HDKeyService implements AuthenticationCallback, PinAuthenticationCallbackInterface
{
    private static final String TAG = "HDWallet";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM;
    private static final String PADDING = KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AUTHENTICATION_DURATION_SECONDS = 30;
    private static final String NO_AUTH_LABEL = "-noauth-";

    public static final int TIME_BETWEEN_BACKUP_MILLIS = 1000 * 60 * 1; //TODO: RESTORE 30 DAYS. TESTING: 1 minute  //1000 * 60 * 60 * 24 * 30; //30 days
    public static final int TIME_BETWEEN_BACKUP_WARNING_MILLIS = 1000 * 60 * 1; //TODO: RESTORE 30 DAYS. TESTING: 1 minute  //1000 * 60 * 60 * 24 * 30; //30 days

    public enum Operation
    {
        CREATE_HD_KEY, FETCH_MNEMONIC, IMPORT_HD_KEY, SIGN_WITH_KEY, CHECK_AUTHENTICATION, SIGN_DATA, CREATE_NON_AUTHENTICATED_KEY, RESTORE_BACKUP_KEY
    }

    public enum AuthenticationLevel
    {
        NOT_SET, NO_AUTHENTICATION, HARDWARE_AUTHENTICATION, STRONGBOX_AUTHENTICATION
    }

    private static final int DEFAULT_KEY_STRENGTH = 128;
    private final Activity context;

    private AuthenticationLevel authLevel;
    private String currentKey;
    private SignTransactionDialog signDialog;
    private AWalletAlertDialog alertDialog;
    private CreateWalletCallbackInterface callbackInterface;
    private ImportWalletCallback importCallback;
    private SignAuthenticationCallback signCallback;

    private static Activity topmostActivity;

    public HDKeyService(Activity ctx)
    {
        System.loadLibrary("TrustWalletCore");
        if (ctx == null)
        {
            context = topmostActivity;
        }
        else
        {
            context = ctx;
            topmostActivity = ctx;
        }
    }

    public void createNewHDKey(CreateWalletCallbackInterface callback)
    {
        callbackInterface = callback;
        callback.setupAuthenticationCallback(this);
        createHDKey();
    }

    public void getMnemonic(String address, CreateWalletCallbackInterface callback)
    {
        currentKey = address;
        callbackInterface = callback;
        callback.setupAuthenticationCallback(this);
        unpackMnemonic(Operation.FETCH_MNEMONIC);
    }

    public void importHDKey(String seedPhrase, ImportWalletCallback callback)
    {
        importCallback = callback;
        callback.setupAuthenticationCallback(this);

        //cursory check for valid key import
        if (!HDWallet.isValid(seedPhrase))
        {
            callback.WalletValidated(null, AuthenticationLevel.NOT_SET);
        }
        else
        {
            currentKey = seedPhrase;
            importHDKey();
        }
    }

    public void getAuthenticationForSignature(String walletAddr, SignAuthenticationCallback callback)
    {
        signCallback = callback;
        callback.setupAuthenticationCallback(this);
        currentKey = walletAddr;
        getAuthenticationForSignature();
    }

    private void getAuthenticationForSignature()
    {
        //check unlock status
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            SecretKey secretKey = (SecretKey) keyStore.getKey(currentKey, null);
            String encryptedHDKeyPath = getFilePath(context, currentKey + "hd");
            boolean fileExists = new File(encryptedHDKeyPath).exists();
            if (!fileExists || secretKey == null)
            {
                signCallback.GotAuthorisation(false);
                return;
            }
            byte[] iv = readBytesFromFile(getFilePath(context, currentKey + "iv"));
            Cipher outCipher = Cipher.getInstance(CIPHER_ALGORITHM);
            final GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            outCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            signCallback.GotAuthorisation(true);
            return;
        }
        catch (UserNotAuthenticatedException e)
        {
            checkAuthentication(Operation.CHECK_AUTHENTICATION);
            return;
        }
        catch (KeyPermanentlyInvalidatedException | UnrecoverableKeyException e)
        {
            //see if we can automatically recover the key
            if (autoRestoreBackupKey(currentKey))
            {
                //TODO: Warn user we just restored a key?
                return;
            }
            else
            {
                keyFailure("Key created at different security level. Please re-import key");
                e.printStackTrace();
            }
        }
        catch (Exception e)
        {
            //some other error, will exit the recursion with bad
            e.printStackTrace();
        }

        signCallback.GotAuthorisation(false);
    }

    //Auth must be unlocked
    synchronized byte[] signData(String key, byte[] transactionBytes)
    {
        currentKey = key;
        String mnemonic = unpackMnemonic(Operation.SIGN_DATA);
        if (mnemonic.length() == 0)
            return "0000".getBytes();
        HDWallet newWallet = new HDWallet(mnemonic, "key1");
        PrivateKey pk = newWallet.getKeyForCoin(CoinType.ETHEREUM);
        byte[] digest = Hash.keccak256(transactionBytes);
        return pk.sign(digest, Curve.SECP256K1);
    }

    private synchronized String unpackMnemonic(Operation operation)
    {
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(currentKey))
            {
                keyFailure("Key not found in keystore. Re-import key.");
                return "";
            }
            String encryptedHDKeyPath = getFilePath(context, currentKey + "hd");
            SecretKey secretKey = (SecretKey) keyStore.getKey(currentKey, null);
            boolean ivExists = new File(getFilePath(context, currentKey + "iv")).exists();
            byte[] iv = null;

            if (ivExists)
                iv = readBytesFromFile(getFilePath(context, currentKey + "iv"));
            if (iv == null || iv.length == 0)
            {
                keyFailure("Cannot setup wallet seed.");
                return "";
            }
            Cipher outCipher = Cipher.getInstance(CIPHER_ALGORITHM);
            final GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            outCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            CipherInputStream cipherInputStream = new CipherInputStream(new FileInputStream(encryptedHDKeyPath), outCipher);
            byte[] mnemonicBytes = readBytesFromStream(cipherInputStream);
            String mnemonic = new String(mnemonicBytes);

            switch (operation)
            {
                case FETCH_MNEMONIC:
                    callbackInterface.FetchMnemonic(mnemonic);
                    break;
                case SIGN_DATA:
                case RESTORE_BACKUP_KEY:
                    return mnemonic;
                default:
                    break;
            }
        }
        catch (InvalidKeyException e)
        {
            if (e instanceof UserNotAuthenticatedException)
            {
                checkAuthentication(operation);
            }
            else
            {
                keyFailure(e.getMessage());
            }
        }
        catch (UnrecoverableKeyException e)
        {
            keyFailure("Key created at different security level. Please re-import key");
            e.printStackTrace();
        }
        catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException e)
        {
            e.printStackTrace();
            keyFailure(e.getMessage());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return "";
    }

    public boolean deleteHDKey(String walletAddr)
    {
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            File hdEncryptedBytes = new File(getFilePath(context, walletAddr + "hd"));
            if (hdEncryptedBytes.exists() && keyStore.containsAlias(walletAddr))
            {
                deleteKey(keyStore, walletAddr);
            }
            return true;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Delete the backup key after user proves they can restore a key
     *
     * @param walletAddr
     * @return
     */
    public boolean deleteBackupKey(String walletAddr)
    {
        String backupKey = walletAddr + NO_AUTH_LABEL;
        return deleteHDKey(backupKey);
    }

    /**
     * Restore the backup key created at key creation in the event that user accidentally destroyed their key before being able to back it up
     *
     * @param walletAddress
     * @param callback
     */
    public void restoreBackupKey(String walletAddress, ImportWalletCallback callback)
    {
        importCallback = callback;
        callback.setupAuthenticationCallback(this);
        //first obtain seed phrase from backup
        currentKey = walletAddress + NO_AUTH_LABEL;
        String mnemonic = unpackMnemonic(Operation.RESTORE_BACKUP_KEY);
        HDWallet newWallet = new HDWallet(mnemonic, "key1");
        storeHDKey(newWallet, Operation.CREATE_HD_KEY);
    }

    private boolean autoRestoreBackupKey(String walletAddress)
    {
        currentKey = walletAddress + NO_AUTH_LABEL;
        String mnemonic = unpackMnemonic(Operation.RESTORE_BACKUP_KEY);
        if (HDWallet.isValid(mnemonic))
        {
            currentKey = mnemonic;
            HDWallet newWallet = new HDWallet(mnemonic, "key1");
            storeHDKey(newWallet, Operation.RESTORE_BACKUP_KEY);
            return true;
        }
        else
        {
            return false;
        }
    }

    private synchronized void deleteKey(KeyStore keyStore, String keyAddr)
    {
        try
        {
            File encrypted = new File(getFilePath(context, keyAddr + "hd"));
            File iv = new File(getFilePath(context, keyAddr + "iv"));
            if (encrypted.exists())
                encrypted.delete();
            if (iv.exists())
                iv.delete();
            if (keyStore != null && keyStore.containsAlias(keyAddr))
                keyStore.deleteEntry(keyAddr);
        }
        catch (KeyStoreException e)
        {
            e.printStackTrace();
        }
    }

    private void createHDKey()
    {
        HDWallet newWallet = new HDWallet(DEFAULT_KEY_STRENGTH, "key1");
        storeHDKey(newWallet, Operation.CREATE_NON_AUTHENTICATED_KEY); //create non-authenticated 'backup' key
        storeHDKey(newWallet, Operation.CREATE_HD_KEY);
    }

    /**
     * Create key from imported seed phrase - note there's no need for non-authenticated backup here as user already has the key
     */
    private void importHDKey()
    {
        HDWallet newWallet = new HDWallet(currentKey, "key1");
        storeHDKey(newWallet, Operation.IMPORT_HD_KEY);
    }

    /**
     * Stores a generated HDWallet in the Android keystore.
     *
     * Operation is:
     * 1. determine HDKey ethereum address
     * 2. generate most secure key possible within constraints - if we're generating a 'backup key' then switch off user Auth
     * 3. attempt to encrypt the HD wallet seed using the generated key. If requires user Auth then go to 5, otherwise 4.
     * 4. Key generation successful - signal to calling process key creation or import is complete along with the new address.
     * 5. Key generation failed because we need a User Authentication event. Fire off the prompt for authentication, after which we start again at 1.
     *
     * @param newWallet
     * @param operation
     */
    private synchronized void storeHDKey(HDWallet newWallet, Operation operation)
    {
        String address = "";
        KeyStore keyStore = null;
        try
        {
            PrivateKey pk = newWallet.getKeyForCoin(CoinType.ETHEREUM);
            address = CoinType.ETHEREUM.deriveAddress(pk);
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            boolean useAuth = true;

            if (operation == Operation.CREATE_NON_AUTHENTICATED_KEY)
            {
                address += NO_AUTH_LABEL;
                useAuth = false;
            }

            if (keyStore.containsAlias(address)) //re-import existing key - no harm done as address is generated from mnemonic
            {
                deleteKey(keyStore, address);
            }

            KeyGenerator keyGenerator = getMaxSecurityKeyGenerator(address, useAuth);
            final SecretKey secretKey = keyGenerator.generateKey();
            final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            String encryptedHDKeyPath = getFilePath(context, address + "hd");
            byte[] iv = cipher.getIV();
            String ivPath = getFilePath(context, address + "iv");
            boolean success = writeBytesToFile(ivPath, iv);
            if (!success)
            {
                deleteKey(keyStore, address);
                failToStore(operation);
                throw new ServiceErrorException(
                        ServiceErrorException.FAIL_TO_SAVE_IV_FILE,
                        "Failed to saveTokens the iv file for: " + address + "iv");
            }

            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(
                    new FileOutputStream(encryptedHDKeyPath),
                    cipher))
            {
                cipherOutputStream.write(newWallet.mnemonic().getBytes());
            }
            catch (Exception ex)
            {
                deleteKey(keyStore, address);
                failToStore(operation);
                throw new ServiceErrorException(
                        ServiceErrorException.KEY_STORE_ERROR,
                        "Failed to saveTokens the file for: " + address);
            }

            //blank class var
            currentKey = null;

            switch (operation)
            {
                case CREATE_HD_KEY:
                    if (callbackInterface != null) callbackInterface.HDKeyCreated(address, context, authLevel);
                    break;
                case IMPORT_HD_KEY:
                    importCallback.WalletValidated(address, authLevel);
                    deleteBackupKey(address); //in the case the user re-imported a key, destroy the backup key
                    break;
                case RESTORE_BACKUP_KEY:
                    Log.d(TAG, "Restored Backup Key");
                default:
                    break;
            }
            return;
        }
        catch (UserNotAuthenticatedException e)
        {
            //delete keys if created
            deleteKey(keyStore, address);
            deleteKey(keyStore, address + NO_AUTH_LABEL);
            //User isn't authenticated, get authentication and start again
            checkAuthentication(operation);
            return;
        }
        catch (Exception ex)
        {
            deleteKey(keyStore, address);
            Log.d(TAG, "Key store error", ex);
        }

        failToStore(operation);
    }

    private void failToStore(Operation operation)
    {
        switch (operation)
        {
            case CREATE_HD_KEY:
                callbackInterface.HDKeyCreated(ZERO_ADDRESS, context, AuthenticationLevel.NOT_SET);
                break;
            case IMPORT_HD_KEY:
                importCallback.WalletValidated(null, AuthenticationLevel.NOT_SET);
                break;
        }
    }

    private KeyGenerator getMaxSecurityKeyGenerator(String keyAddress, boolean useAuthentication)
    {
        KeyGenerator keyGenerator;

        try
        {
            keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEY_STORE);
        }
        catch (NoSuchAlgorithmException | NoSuchProviderException ex)
        {
            ex.printStackTrace();
            return null;
        }

        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tryInitStrongBoxKey(keyGenerator, keyAddress, useAuthentication))
            {
                Log.d(TAG, "Using Strongbox");
                authLevel = AuthenticationLevel.STRONGBOX_AUTHENTICATION;
            }
            else if (tryInitTEEKey(keyGenerator, keyAddress, useAuthentication))
            {
                //fallback to non Strongbox
                Log.d(TAG, "Using Hardware security TEE");
                authLevel = AuthenticationLevel.HARDWARE_AUTHENTICATION;
            }
            else if (tryInitTEEKey(keyGenerator, keyAddress, false))
            {
                Log.d(TAG, "Using Hardware security TEE without authentication");
                authLevel = AuthenticationLevel.NO_AUTHENTICATION;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            //handle unable to generate key - should be impossible to get here after API 19
        }

        return keyGenerator;
    }

    private boolean tryInitStrongBoxKey(KeyGenerator keyGenerator, String keyAddress, boolean useAuthentication) throws InvalidAlgorithmParameterException
    {
        try
        {
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    keyAddress,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                      .setBlockModes(BLOCK_MODE)
                                      .setKeySize(256)
                                      .setUserAuthenticationRequired(useAuthentication)
                                      .setIsStrongBoxBacked(true)
                                      .setUserAuthenticationValidityDurationSeconds(AUTHENTICATION_DURATION_SECONDS)
                                      .setRandomizedEncryptionRequired(true)
                                      .setEncryptionPaddings(PADDING)
                                      .build());

            keyGenerator.generateKey();
        }
        catch (StrongBoxUnavailableException e)
        {
            Log.d(TAG, "Android 9 device doesn't have StrongBox");
            return false;
        }

        return true;
    }

    private boolean tryInitTEEKey(KeyGenerator keyGenerator, String keyAddress, boolean useAuthentication)
    {
        try
        {
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    keyAddress,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                      .setBlockModes(BLOCK_MODE)
                                      .setKeySize(256)
                                      .setUserAuthenticationRequired(useAuthentication)
                                      .setUserAuthenticationValidityDurationSeconds(AUTHENTICATION_DURATION_SECONDS)
                                      .setRandomizedEncryptionRequired(true)
                                      .setEncryptionPaddings(PADDING)
                                      .build());
        }
        catch (IllegalStateException | InvalidAlgorithmParameterException e)
        {
            //couldn't create the key because of no lock
            return false;
        }

        return true;
    }

    private void checkAuthentication(Operation operation)
    {
        signDialog = new SignTransactionDialog(context, operation.ordinal());
        signDialog.setCanceledOnTouchOutside(false);
        signDialog.setCancelListener(v -> {
            authenticateFail("Cancelled", AuthenticationFailType.AUTHENTICATION_DIALOG_CANCELLED, operation.ordinal());
        });
        signDialog.show();
        signDialog.getFingerprintAuthorisation(this);
    }

    private synchronized static String getFilePath(Context context, String fileName)
    {
        return new File(context.getFilesDir(), fileName).getAbsolutePath();
    }

    private static boolean writeBytesToFile(String path, byte[] data)
    {
        FileOutputStream fos = null;
        try
        {
            File file = new File(path);
            fos = new FileOutputStream(file);
            // Writes bytes from the specified byte array to this file output stream
            fos.write(data);
            return true;
        }
        catch (FileNotFoundException e)
        {
            System.out.println("File not found" + e);
        }
        catch (IOException ioe)
        {
            System.out.println("Exception while writing file " + ioe);
        }
        finally
        {
            // close the streams using close method
            try
            {
                if (fos != null)
                {
                    fos.close();
                }
            }
            catch (IOException ioe)
            {
                System.out.println("Error while closing stream: " + ioe);
            }
        }
        return false;
    }

    private static byte[] readBytesFromFile(String path)
    {
        byte[] bytes = null;
        FileInputStream fin;
        try
        {
            File file = new File(path);
            fin = new FileInputStream(file);
            bytes = readBytesFromStream(fin);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return bytes;
    }


    private static byte[] readBytesFromStream(InputStream in)
    {
        // this dynamically extends to take the bytes you read
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        // this is storage overwritten on each iteration with bytes
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];
        // we need to know how may bytes were read to write them to the byteBuffer
        int len;
        try
        {
            while ((len = in.read(buffer)) != -1)
            {
                byteBuffer.write(buffer, 0, len);
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        finally
        {
            try
            {
                byteBuffer.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            if (in != null)
                try
                {
                    in.close();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
        }
        // and then we can return your byte array.
        return byteBuffer.toByteArray();
    }

    @Override
    public void CompleteAuthentication(int callbackId)
    {
        authenticatePass(callbackId);
    }

    @Override
    public void FailedAuthentication(int taskCode)
    {
        authenticateFail("Authentication fail", AuthenticationFailType.PIN_FAILED, taskCode);
    }

    @Override
    public void authenticatePass(int callbackId)
    {
        if (signDialog != null && signDialog.isShowing())
            signDialog.dismiss();
        //resume key operation
        Operation operation = Operation.values()[callbackId];
        switch (operation)
        {
            case CREATE_HD_KEY:
                createHDKey();
                break;
            case FETCH_MNEMONIC:
                unpackMnemonic(Operation.FETCH_MNEMONIC);
                break;
            case IMPORT_HD_KEY:
                importHDKey();
                break;
            case CHECK_AUTHENTICATION:
                getAuthenticationForSignature();
                break;
            case RESTORE_BACKUP_KEY:
                HDWallet newWallet = new HDWallet(currentKey, "key1");
                storeHDKey(newWallet, Operation.RESTORE_BACKUP_KEY);
                break;
            default:
                break;
        }
    }

    @Override
    public void authenticateFail(String fail, AuthenticationFailType failType, int callbackId)
    {
        System.out.println("AUTH FAIL: " + failType.ordinal());
        Vibrator vb;

        switch (failType)
        {
            case AUTHENTICATION_DIALOG_CANCELLED:
                cancelAuthentication();
                if (signDialog != null && signDialog.isShowing())
                    signDialog.dismiss();
                break;
            case FINGERPRINT_NOT_VALIDATED:
                vb = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vb != null && vb.hasVibrator())
                {
                    VibrationEffect vibe = VibrationEffect.createOneShot(200, DEFAULT_AMPLITUDE);
                    vb.vibrate(vibe);
                }
                Toast.makeText(context, "Fingerprint authentication failed", Toast.LENGTH_SHORT).show();
                break;
            case PIN_FAILED:
                vb = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vb != null && vb.hasVibrator())
                {
                    VibrationEffect vibe = VibrationEffect.createOneShot(200, DEFAULT_AMPLITUDE);
                    vb.vibrate(vibe);
                }
                break;
            case DEVICE_NOT_SECURE:
                //Note:- allowing user to create a key with no auth-unlock ensures we should never get here
                //Handle some sort of edge condition where the user gets here.
                showInsecure(callbackId);
                break;
        }

        if (context == null || context.isDestroyed())
        {
            cancelAuthentication();
        }
    }

    private void keyFailure(String message)
    {
        if (message == null || message.length() == 0 || !AuthorisationFailMessage(message))
        {
            if (callbackInterface != null)
                callbackInterface.keyFailure(message);
            else if (signCallback != null)
                signCallback.GotAuthorisation(false);
        }
    }

    private void cancelAuthentication()
    {
        if (callbackInterface != null)
            callbackInterface.cancelAuthentication();
        else if (signCallback != null)
            signCallback.GotAuthorisation(false);
    }

    public boolean isChecking()
    {
        return (signDialog != null && signDialog.isShowing());
    }

    private boolean AuthorisationFailMessage(String message)
    {
        if (alertDialog != null && alertDialog.isShowing())
            alertDialog.dismiss();
        if (context == null || context.isDestroyed())
            return false;

        alertDialog = new AWalletAlertDialog(context);
        alertDialog.setIcon(AWalletAlertDialog.ERROR);
        alertDialog.setTitle(R.string.key_error);
        alertDialog.setMessage(message);
        alertDialog.setButtonText(R.string.action_continue);
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialog.setButtonListener(v -> {
            keyFailure("");
            alertDialog.dismiss();
        });
        alertDialog.setOnCancelListener(v -> {
            keyFailure("");
            cancelAuthentication();
        });
        alertDialog.show();

        return true;
    }

    /**
     * Current behaviour: Allow user to create unsecured key
     *
     * @param callbackId
     */
    private void showInsecure(int callbackId)
    {
        AWalletAlertDialog dialog = new AWalletAlertDialog(context);
        dialog.setIcon(AWalletAlertDialog.ERROR);
        dialog.setTitle(R.string.device_insecure);
        dialog.setMessage(R.string.device_not_secure_warning);
        dialog.setButtonText(R.string.action_continue);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setButtonListener(v -> {
            cancelAuthentication();
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * Return a list of HD Key wallets in date order, from first created
     *
     * @return List of Wallet, date ordered
     */
    public List<Wallet> getAllHDWallets()
    {
        List<Wallet> wallets = new ArrayList<>();
        try
        {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            Enumeration<String> keys = keyStore.aliases();
            List<Date> fileDates = new ArrayList<>();
            Map<Date, Wallet> walletMap = new HashMap<>();

            while (keys.hasMoreElements())
            {
                String alias = keys.nextElement();
                File hdEncryptedBytes = new File(getFilePath(context, alias + "hd"));
                if (!alias.contains(NO_AUTH_LABEL) && hdEncryptedBytes.exists())
                {
                    Date date = new Date(hdEncryptedBytes.lastModified());
                    fileDates.add(date);
                    if (!alias.startsWith("0x")) alias = "0x" + alias;
                    Wallet hdKey = new Wallet(alias);
                    hdKey.type = WalletType.HDKEY;
                    System.out.println("Key: " + alias);
                    walletMap.put(date, hdKey);
                }
            }

            Collections.sort(fileDates);

            for (Date d : fileDates)
            {
                wallets.add(walletMap.get(d));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return wallets;
    }

    public static void setTopmostActivity(Activity activity)
    {
        topmostActivity = activity;
    }
}
