package org.yinwang.pysonar.demos;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yinwang.pysonar.*;
import org.yinwang.pysonar.types.ModuleType;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * Collects per-file hyperlinks, as well as styles that require the
 * symbol table to resolve properly.
 */
class Linker {

    private static final Pattern CONSTANT = Pattern.compile("[A-Z_][A-Z0-9_]*");

    // Map of file-path to semantic styles & links for that path.
    @NotNull
    private Map<String, List<StyleRun>> fileStyles = new HashMap<String, List<StyleRun>>();

    private File outDir;  // where we're generating the output html
    private String rootPath;

    /**
     * Constructor.
     * @param root the root of the directory tree being indexed
     * @param outdir the html output directory
     */
    public Linker(String root, File outdir) {
        rootPath = root;
        outDir = outdir;
    }

    /**
     * Process all bindings across all files and record per-file semantic styles.
     * Should be called once per index.
     */

    int nDef = 0;

    public void findLinks(@NotNull Indexer indexer) {
        Util.msg("Adding xref links for " + indexer.getAllBindings().size() + " bindings");

        for (List<Binding> bindings : indexer.getAllBindings().values()) {
            for (Binding b : bindings) {
                addSemanticStyles(b);
                for (Def def : b.getDefs()) {
                    processDef(def, b);
                    nDef++;
                }
            }
        }

        // highlight definitions
        Util.msg("\nAdding ref links");
        for (Entry<Ref,List<Binding>> e : indexer.getReferences().entrySet()) {
            processRef(e.getKey(), e.getValue());
        }

        Util.msg("\n#xrefs: " + nXref);
        Util.msg("#defs: " + nDef);
        Util.msg("#refs: " + nRef);

//        for (List<Diagnostic> ld: indexer.semanticErrors.values()) {
//            for (Diagnostic d: ld) {
//                processDiagnostic(d);
//            }
//        }

//        for (List<Diagnostic> ld: indexer.parseErrors.values()) {
//            for (Diagnostic d: ld) {
//                processDiagnostic(d);
//            }
//        }
    }


    int nXref = 0;
    Progress progressXRef = new Progress(10000, 50);

    private void processDef(@Nullable Def def, @NotNull Binding binding) {
        if (def == null || def.isURL() || def.getStart() < 0) {
            return;
        }
        StyleRun style = new StyleRun(StyleRun.Type.ANCHOR, def.getStart(), def.getLength());
        style.message = binding.getQname() + " : " + binding.getType();
        style.url = binding.getQname();
        style.id = "" + Math.abs(def.hashCode());

        Set<Ref> refs = binding.getRefs();
        style.highlight = new ArrayList<>();


        for (Ref r : refs) {
            progressXRef.tick();
            nXref++;
            style.highlight.add(Integer.toString(Math.abs(r.hashCode())));
        }
        addFileStyle(def.getFile(), style);
    }


    int nRef = 0;
    Progress progressRef = new Progress(10000, 50);

    void processRef(@NotNull Ref ref, @NotNull List<Binding> bindings) {
        StyleRun link = new StyleRun(StyleRun.Type.LINK, ref.start(), ref.length());
        link.id = Integer.toString(Math.abs(ref.hashCode()));

        List<String> typings = new ArrayList<>();
        for (Binding b : bindings) {
            typings.add(b.getQname() + " : " + b.getType());
        }
        link.message = Util.joinWithSep(typings, " | ", "{", "}");

        link.highlight = new ArrayList<>();

        for (Binding b : bindings) {
            for (Def d : b.getDefs()) {
                progressRef.tick();
                nRef++;
                link.highlight.add(Integer.toString(Math.abs(d.hashCode())));
            }
        }


        // Currently jump to the first binding only. Should change to have a
        // hover menu or something later.
        String path = ref.getFile();
        for (Binding b : bindings) {
            if (link.url == null) {
                link.url = toURL(b, path);
            }

            if (link.url != null) {
                addFileStyle(path, link);
                break;
            }
        }
    }


    /**
     * Returns the styles (links and extra styles) generated for a given file.
     * @param path an absolute source path
     * @return a possibly-empty list of styles for that path
     */
    public List<StyleRun> getStyles(String path) {
        return stylesForFile(path);
    }

    private List<StyleRun> stylesForFile(String path) {
        List<StyleRun> styles = fileStyles.get(path);
        if (styles == null) {
            styles = new ArrayList<StyleRun>();
            fileStyles.put(path, styles);
        }
        return styles;
    }

    private void addFileStyle(String path, StyleRun style) {
        stylesForFile(path).add(style);
    }

    /**
     * Add additional highlighting styles based on information not evident from
     * the AST.
     */
    private void addSemanticStyles(@NotNull Binding nb) {
        Def def = nb.getSingle();
        if (def == null || !def.isName()) {
            return;
        }

        boolean isConst = CONSTANT.matcher(def.getName()).matches();
        switch (nb.getKind()) {
            case SCOPE:
                if (isConst) {
                    addSemanticStyle(def, StyleRun.Type.CONSTANT);
                }
                break;
            case VARIABLE:
                addSemanticStyle(def, isConst ? StyleRun.Type.CONSTANT : StyleRun.Type.IDENTIFIER);
                break;
            case PARAMETER:
                addSemanticStyle(def, StyleRun.Type.PARAMETER);
                break;
            case CLASS:
                addSemanticStyle(def, StyleRun.Type.TYPE_NAME);
                break;
        }
    }

    private void addSemanticStyle(@NotNull Def def, StyleRun.Type type) {
        String path = def.getFile();
        if (path != null) {
            addFileStyle(path, new StyleRun(type, def.getStart(), def.getLength()));
        }
    }


    private void processDiagnostic(@NotNull Diagnostic d) {
        StyleRun style = new StyleRun(StyleRun.Type.WARNING, d.start, d.end - d.start);
        style.message = d.msg;
        style.url = d.file;
        addFileStyle(d.file, style);
    }

    
    /**
     * Generate a URL for a reference to a binding.
     * @param binding the referenced binding
     * @param filename the path containing the reference, or null if there was an error
     */
    @Nullable
    private String toURL(@NotNull Binding binding, String filename) {
        Def def = binding.getSingle();

        if (binding.isBuiltin()) {
            return def.getURL();
        }

        String destPath = null;

        if (binding.getKind() == Binding.Kind.MODULE) {
            ModuleType mtype = binding.getType().asModuleType();
            if (mtype != null) {
                destPath = mtype.getFile();
            }
        } else {
            destPath = def.getFile();
        }

        if (destPath == null) {
            return null;
        }

        String anchor = "#" + binding.getQname();
        if (binding.getFirstFile().equals(filename)) {
            return anchor;
        }

        if (destPath.startsWith(rootPath)) {
            String relpath;
            if (filename != null) {
                relpath = Util.relativize(filename, destPath);
            } else {
                relpath = destPath;
            }

            if (relpath != null) {
                return relpath + ".html" + anchor;
            } else {
                return anchor;
            }
        } else {
            return "file://" + destPath + anchor;
        }
    }

}