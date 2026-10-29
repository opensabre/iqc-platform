package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact version lookup. Missing adapters remain unavailable; there is no simulated fallback. */
@Component
public class ActionAdapterRegistry {
    private record Key(ActionExecutionContract.Type type,int version) { }
    public record Binding(ActionAdapter.Definition definition,ActionAdapter adapter) { }
    private final Map<Key,Binding> bindings;
    public ActionAdapterRegistry(List<ActionAdapter> adapters) {
        var registered=new HashMap<Key,Binding>();
        for(var adapter:adapters) {
            var definition=Objects.requireNonNull(adapter.definition(),"Action adapter definition required");
            var key=new Key(definition.type(),definition.version());
            if(registered.putIfAbsent(key,new Binding(definition,adapter))!=null)
                throw new IllegalStateException("Duplicate action adapter type/version");
        }
        bindings=Map.copyOf(registered);
    }
    public Binding require(ActionPolicy.Action action) {
        Objects.requireNonNull(action);
        var binding=bindings.get(new Key(action.type(),action.effectiveAdapterVersion()));
        if(binding==null)throw IqcException.invalidState("指定版本的动作适配器未提供");
        if(!binding.definition().equals(binding.adapter().definition()))
            throw IqcException.invalidState("动作适配协议发生变化");
        binding.adapter().validateConfiguration(action.configuration());
        return binding;
    }
}
