package com.ecl.modrinth.ui.viewmodel;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.provider.ModMetadataProvider;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Searches compatible projects and discards results from superseded search requests. */
final class ModBrowserSearchController {
    private final StringProperty searchText;
    private final ObjectProperty<ModSearchIndex> sortIndex;
    private final ObservableList<ModProject> results;
    private final Supplier<ModInstanceContext> instanceSupplier;
    private final ModBrowserOperationState operations;
    private final Consumer<String> setError;
    private final Consumer<String> setOperation;
    private final java.util.function.Function<Throwable, String> errorFormatter;
    private final AtomicLong generation = new AtomicLong();
    private ModMetadataProvider provider;
    private final AtomicInteger offset = new AtomicInteger();
    private String category = "";

    ModBrowserSearchController(ModMetadataProvider provider, StringProperty searchText,
                               ObjectProperty<ModSearchIndex> sortIndex,
                               ObservableList<ModProject> results,
                               Supplier<ModInstanceContext> instanceSupplier,
                               ModBrowserOperationState operations, Consumer<String> setError,
                               Consumer<String> setOperation,
                               java.util.function.Function<Throwable, String> errorFormatter) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.searchText = searchText;
        this.sortIndex = sortIndex;
        this.results = results;
        this.instanceSupplier = instanceSupplier;
        this.operations = operations;
        this.setError = setError;
        this.setOperation = setOperation;
        this.errorFormatter = errorFormatter;
    }

    void setProvider(ModMetadataProvider value) {
        provider = Objects.requireNonNull(value, "provider");
        reset();
    }

    void setCategory(String value) {
        category = value == null ? "" : value.trim();
    }

    void reset() {
        generation.incrementAndGet();
        offset.set(0);
        results.clear();
    }

    void search(boolean append) {
        ModInstanceContext context = instanceSupplier.get();
        if (!context.loader().supportsMods()) {
            setError.accept("当前是原版实例，请先选择 Fabric、Quilt、Forge 或 NeoForge 实例。");
            results.clear();
            return;
        }
        if (!append) {
            offset.set(0);
            results.clear();
        }
        long requestGeneration = generation.incrementAndGet();
        operations.cancel();
        operations.begin("正在搜索兼容模组…", true);
        Set<String> categories = category.isBlank() ? Set.of() : Set.of(category);
        ModSearchQuery query = new ModSearchQuery(searchText.get(), context.minecraftVersion(),
                context.loaderName(), categories, sortIndex.get(), offset.get(), 20);
        var request = provider.search(query).whenComplete((result, error) -> Platform.runLater(() -> {
            if (requestGeneration != generation.get()) {
                return;
            }
            operations.finish();
            if (error != null) {
                setError.accept(errorFormatter.apply(error));
                return;
            }
            if (append) {
                results.addAll(result.hits());
            } else {
                results.setAll(result.hits());
            }
            offset.addAndGet(result.hits().size());
            setOperation.accept(result.hits().isEmpty()
                    ? "没有找到兼容结果" : "已加载 " + results.size() + " 个结果");
        }));
        operations.track(request);
    }
}
