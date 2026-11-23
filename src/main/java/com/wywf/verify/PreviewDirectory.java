package com.wywf.verify;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;

public final class PreviewDirectory {
    public static final String PREFIX = ".wywf-preview-";
    private static final String OWNER = ".wywf-owned";
    private static final String ACCEPTED = ".wywf-accepted";

    public static Path create(Path saves) throws IOException {
        Path root = saves.toAbsolutePath().normalize().toRealPath();
        Path child = root.resolve(PREFIX + UUID.randomUUID());
        Files.createDirectory(child);
        Files.writeString(child.resolve(OWNER), child.getFileName().toString(), StandardOpenOption.CREATE_NEW);
        return child;
    }

    public static boolean hidden(Path path) {
        return path.getFileName().toString().startsWith(PREFIX) && Files.isRegularFile(path.resolve(OWNER))
                && !Files.exists(path.resolve(ACCEPTED));
    }

    public static void accept(Path path) throws IOException {
        if (!owned(path)) throw new IOException("Preview ownership mismatch");
        Files.writeString(path.resolve(ACCEPTED), "accepted", StandardOpenOption.CREATE_NEW);
    }

    public static boolean accepted(Path path) throws IOException {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path.resolve(ACCEPTED),LinkOption.NOFOLLOW_LINKS))return false;
        if(owned(path))return true;
        if(!Files.isRegularFile(path.resolve(OWNER),LinkOption.NOFOLLOW_LINKS)||Files.size(path.resolve(OWNER))>128)return false;
        String owner=Files.readString(path.resolve(OWNER));
        if(!owner.startsWith(PREFIX))return false;
        try{UUID.fromString(owner.substring(PREFIX.length()));return true;}
        catch(IllegalArgumentException error){return false;}
    }

    public static Path promote(Path path,String splash)throws IOException {
        Path source=path.toAbsolutePath().normalize();
        Path parent=source.getParent().toRealPath();
        if(!source.getParent().equals(parent)||!owned(source)||!accepted(source))throw new IOException("Only an accepted owned preview can be renamed");
        String name="WyWF - "+splash.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "").replaceAll("[^A-Za-z0-9 -]", "").strip();
        if(name.length()>90||name.length()<=7)throw new IOException("Invalid world splash name");
        for(int n=1;n<=10000;n++){
            Path target=parent.resolve(name+(n==1?"":" ("+n+")")).normalize();
            if(!target.getParent().equals(parent))throw new IOException("World name escapes saves directory");
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))continue;
            try{return Files.move(source,target);}
            catch(FileAlreadyExistsException collision){}
        }
        throw new IOException("No free splash world name");
    }

    private static boolean owned(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (!name.startsWith(PREFIX) || Files.isSymbolicLink(path)) return false;
        try { UUID.fromString(name.substring(PREFIX.length())); }
        catch (IllegalArgumentException error) { return false; }
        return Files.isRegularFile(path.resolve(OWNER), LinkOption.NOFOLLOW_LINKS) && Files.size(path.resolve(OWNER))<=128
                && Files.readString(path.resolve(OWNER)).equals(name);
    }

    public static void discard(Path saves, Path path) throws IOException {
        Path parent = saves.toAbsolutePath().normalize().toRealPath();
        Path child = path.toAbsolutePath().normalize();
        if (!child.getParent().equals(parent) || !owned(child) || Files.exists(child.resolve(ACCEPTED)))
            throw new IOException("Refusing to delete an unowned or accepted world");
        try (var paths = Files.walk(child)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }

    private PreviewDirectory() {}
}
