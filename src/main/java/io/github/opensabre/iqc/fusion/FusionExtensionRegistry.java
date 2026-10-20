package io.github.opensabre.iqc.fusion;

import java.util.*;
import java.security.MessageDigest;

/** Immutable, exact version lookup over deployment-installed providers. No mutable global registration. */
public final class FusionExtensionRegistry {
    private record Key(String strategyId,int version) { }
    private record Installed(FusionExtension.Definition definition,String hash,FusionExtension provider) { }
    public record Descriptor(String strategyId,int version,String implementationHash,String name,Map<String,String> defaultParameters) { }
    private final Map<Key,Installed> installed;
    public FusionExtensionRegistry(List<FusionExtension> providers){
        if(providers.size()>64)throw new IllegalArgumentException("At most 64 fusion extension versions may be installed");
        var entries=new HashMap<Key,Installed>();
        for(var provider:providers){
            var definition=Objects.requireNonNull(provider.definition());provider.validateParameters(definition.defaultParameters());
            if(entries.putIfAbsent(new Key(definition.strategyId(),definition.version()),new Installed(definition,implementationHash(provider),provider))!=null)
                throw new IllegalArgumentException("Duplicate fusion extension strategy/version");
        }
        installed=Map.copyOf(entries);
    }
    private static final class SystemRegistry {
        private static final FusionExtensionRegistry INSTANCE=load();
        private static FusionExtensionRegistry load(){
            var providers=new ArrayList<FusionExtension>();providers.add(new UnanimousFusionExtension());
            for(var provider:ServiceLoader.load(FusionExtension.class,FusionExtension.class.getClassLoader())){
                if(providers.size()>=64)throw new IllegalStateException("Too many installed fusion extensions");providers.add(provider);
            }
            return new FusionExtensionRegistry(providers);
        }
    }
    public static FusionExtensionRegistry system(){return SystemRegistry.INSTANCE;}
    public List<Descriptor> descriptors(){
        return installed.values().stream().map(binding->new Descriptor(binding.definition().strategyId(),binding.definition().version(),binding.hash(),binding.definition().name(),binding.definition().defaultParameters()))
                .sorted(Comparator.comparing(Descriptor::strategyId).thenComparingInt(Descriptor::version)).toList();
    }
    public FusionExtension require(FusionExtensionBinding binding){
        Objects.requireNonNull(binding);var found=installed.get(new Key(binding.strategyId(),binding.version()));
        if(found==null||!found.hash().equals(binding.implementationHash()))throw new IllegalArgumentException("Frozen fusion extension implementation is unavailable");
        if(!found.definition().equals(found.provider().definition()))throw new IllegalStateException("Fusion extension definition changed");
        found.provider().validateParameters(binding.parameters());return found.provider();
    }
    private static String implementationHash(FusionExtension provider){
        var type=provider.getClass();var path="/"+type.getName().replace('.','/')+".class";
        try(var input=type.getResourceAsStream(path)){
            if(input==null)throw new IllegalStateException("Fusion extension implementation bytes unavailable");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException("Fusion extension identity unavailable",invalid);}
    }
}
