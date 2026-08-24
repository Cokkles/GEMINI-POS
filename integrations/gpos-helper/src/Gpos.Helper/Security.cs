using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Options;

namespace Gpos.Helper;

public interface ISecretStore
{
    Task SaveAsync(string name, string value, CancellationToken cancellationToken);
    Task<string?> GetAsync(string name, CancellationToken cancellationToken);
    Task DeleteAsync(string name, CancellationToken cancellationToken);
}

public sealed class WindowsDpapiSecretStore(IHostEnvironment environment) : ISecretStore
{
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GEMINI-POS", "gpos-helper", environment.EnvironmentName);

    public async Task SaveAsync(string name, string value, CancellationToken cancellationToken)
    {
        EnsureWindows();
        Directory.CreateDirectory(_directory);
        var clear = Encoding.UTF8.GetBytes(value);
        try { await File.WriteAllBytesAsync(PathFor(name), Protect(clear), cancellationToken); }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }

    public async Task<string?> GetAsync(string name, CancellationToken cancellationToken)
    {
        EnsureWindows();
        var path = PathFor(name);
        if (!File.Exists(path)) return null;
        var cipher = await File.ReadAllBytesAsync(path, cancellationToken);
        var clear = Unprotect(cipher);
        try { return Encoding.UTF8.GetString(clear); }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }

    public Task DeleteAsync(string name, CancellationToken cancellationToken)
    {
        EnsureWindows();
        var path = PathFor(name);
        if (File.Exists(path)) File.Delete(path);
        return Task.CompletedTask;
    }

    private string PathFor(string name) => Path.Combine(_directory, Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(name))) + ".bin");
    private static void EnsureWindows() { if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("DPAPI secret storage requires Windows."); }

    private static byte[] Protect(byte[] input) => Crypt(input, true);
    private static byte[] Unprotect(byte[] input) => Crypt(input, false);

    private static byte[] Crypt(byte[] input, bool protect)
    {
        var inputBlob = new DataBlob();
        var outputBlob = new DataBlob();
        try
        {
            inputBlob.Size = input.Length;
            inputBlob.Data = Marshal.AllocHGlobal(input.Length);
            Marshal.Copy(input, 0, inputBlob.Data, input.Length);
            var ok = protect
                ? CryptProtectData(ref inputBlob, "gpos-helper", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, ref outputBlob)
                : CryptUnprotectData(ref inputBlob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, ref outputBlob);
            if (!ok) throw new CryptographicException(Marshal.GetLastWin32Error());
            var result = new byte[outputBlob.Size];
            Marshal.Copy(outputBlob.Data, result, 0, outputBlob.Size);
            return result;
        }
        finally
        {
            if (inputBlob.Data != IntPtr.Zero) { ZeroMemory(inputBlob.Data, (nuint)inputBlob.Size); Marshal.FreeHGlobal(inputBlob.Data); }
            if (outputBlob.Data != IntPtr.Zero) { ZeroMemory(outputBlob.Data, (nuint)outputBlob.Size); LocalFree(outputBlob.Data); }
        }
    }

    [StructLayout(LayoutKind.Sequential)] private struct DataBlob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool CryptProtectData(ref DataBlob dataIn, string description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref DataBlob dataOut);
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptUnprotectData(ref DataBlob dataIn, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref DataBlob dataOut);
    [DllImport("kernel32.dll", SetLastError = false)] private static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("kernel32.dll", EntryPoint = "RtlZeroMemory")] private static extern void ZeroMemory(IntPtr destination, nuint length);
}

public sealed class EphemeralSecretStore : ISecretStore
{
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, string> values = new();
    public Task SaveAsync(string name, string value, CancellationToken cancellationToken) { values[name] = value; return Task.CompletedTask; }
    public Task<string?> GetAsync(string name, CancellationToken cancellationToken) => Task.FromResult(values.GetValueOrDefault(name));
    public Task DeleteAsync(string name, CancellationToken cancellationToken) { values.TryRemove(name, out _); return Task.CompletedTask; }
}

public sealed class PortableAesSecretStore : ISecretStore, IDisposable
{
    private readonly string directory;
    private readonly byte[] key;

    public PortableAesSecretStore(IOptions<HelperOptions> options)
    {
        directory = options.Value.SecretStorePath;
        if (string.IsNullOrWhiteSpace(directory) || string.IsNullOrWhiteSpace(options.Value.SecretStoreKeyFile)) throw new InvalidOperationException("Portable secret storage is not configured.");
        var encoded = File.ReadAllText(options.Value.SecretStoreKeyFile).Trim();
        try { key = Convert.FromBase64String(encoded); }
        catch (FormatException) { throw new InvalidOperationException("Portable secret-store key must be base64 encoded."); }
        if (key.Length != 32) { CryptographicOperations.ZeroMemory(key); throw new InvalidOperationException("Portable secret-store key must contain exactly 32 bytes."); }
        Directory.CreateDirectory(directory);
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }

    public async Task SaveAsync(string name, string value, CancellationToken cancellationToken)
    {
        var clear = Encoding.UTF8.GetBytes(value); var nonce = RandomNumberGenerator.GetBytes(12); var tag = new byte[16]; var cipher = new byte[clear.Length];
        try
        {
            using var aes = new AesGcm(key, 16); aes.Encrypt(nonce, clear, cipher, tag, Encoding.UTF8.GetBytes(name));
            var payload = new byte[1 + nonce.Length + tag.Length + cipher.Length]; payload[0] = 1;
            nonce.CopyTo(payload, 1); tag.CopyTo(payload, 13); cipher.CopyTo(payload, 29);
            var path = PathFor(name); var temporary = path + ".tmp-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(6));
            await File.WriteAllBytesAsync(temporary, payload, cancellationToken);
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temporary, path, true);
        }
        finally { CryptographicOperations.ZeroMemory(clear); CryptographicOperations.ZeroMemory(cipher); }
    }

    public async Task<string?> GetAsync(string name, CancellationToken cancellationToken)
    {
        var path = PathFor(name); if (!File.Exists(path)) return null;
        var payload = await File.ReadAllBytesAsync(path, cancellationToken);
        if (payload.Length < 29 || payload[0] != 1) throw new CryptographicException("Portable secret payload is invalid.");
        var clear = new byte[payload.Length - 29];
        try { using var aes = new AesGcm(key, 16); aes.Decrypt(payload.AsSpan(1, 12), payload.AsSpan(29), payload.AsSpan(13, 16), clear, Encoding.UTF8.GetBytes(name)); return Encoding.UTF8.GetString(clear); }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }

    public Task DeleteAsync(string name, CancellationToken cancellationToken) { var path = PathFor(name); if (File.Exists(path)) File.Delete(path); return Task.CompletedTask; }
    public void Dispose() => CryptographicOperations.ZeroMemory(key);
    private string PathFor(string name) => Path.Combine(directory, Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(name))) + ".secret");
}

public static partial class SecretRedactor
{
    public static string Redact(string? input)
    {
        if (string.IsNullOrEmpty(input)) return input ?? "";
        var value = BearerRegex().Replace(input, "$1[REDACTED]");
        value = JsonSecretRegex().Replace(value, "$1[REDACTED]$2");
        value = QuerySecretRegex().Replace(value, "$1[REDACTED]");
        return value;
    }

    [GeneratedRegex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*")] private static partial Regex BearerRegex();
    [GeneratedRegex("(?i)(\\\"(?:access_token|refresh_token|id_token|auth_token|client_secret|password|api_key)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")")] private static partial Regex JsonSecretRegex();
    [GeneratedRegex("(?i)((?:token|key|secret|password)=)[^&\\s]+")] private static partial Regex QuerySecretRegex();
}
