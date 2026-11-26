package com.ygoleague.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Images as files under {@code app.images.local-dir}, laid out as {@link ImageKey#path()}. */
@Component
@ConditionalOnProperty(name = "app.images.store", havingValue = "local", matchIfMissing = true)
class LocalDiskImageStore implements ImageStore {

    private final Path root;

    LocalDiskImageStore(@Value("${app.images.local-dir}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Optional<byte[]> get(ImageKey key) {
        try {
            return Optional.of(Files.readAllBytes(file(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(ImageKey key) {
        return Files.isRegularFile(file(key));
    }

    @Override
    public void put(ImageKey key, byte[] jpeg) {
        Path target = file(key);
        try {
            Files.createDirectories(target.getParent());
            // Write to a temp file in the same folder, then rename: the rename is atomic.
            Path tmp = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
            try {
                Files.write(tmp, jpeg);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(ImageKey key) {
        // The key is built from an enum and a number, so it can't escape the root; checked anyway.
        Path file = root.resolve(key.path()).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Bad image key " + key);
        return file;
    }
}
