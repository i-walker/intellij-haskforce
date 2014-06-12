package com.haskforce.parsing;

import com.haskforce.parsing.jsonParser.JsonParser;
import com.haskforce.parsing.srcExtsDatatypes.*;
import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import static com.haskforce.parsing.HaskellTypes2.*;
// These can be imported as * when the old parser is removed.
import static com.haskforce.psi.HaskellTypes.OPENPRAGMA;
import static com.haskforce.psi.HaskellTypes.CLOSEPRAGMA;
import static com.haskforce.psi.HaskellTypes.OPENCOM;
import static com.haskforce.psi.HaskellTypes.CLOSECOM;
import static com.haskforce.psi.HaskellTypes.CPPIF;
import static com.haskforce.psi.HaskellTypes.CPPELSE;
import static com.haskforce.psi.HaskellTypes.CPPENDIF;
import static com.haskforce.psi.HaskellTypes.COMMENT;
import static com.haskforce.psi.HaskellTypes.COMMENTTEXT;
import static com.haskforce.psi.HaskellTypes.DOUBLEQUOTE;
import static com.haskforce.psi.HaskellTypes.STRINGTOKEN;
import static com.haskforce.psi.HaskellTypes.BADSTRINGTOKEN;
import static com.haskforce.psi.HaskellTypes.MODULE;
import static com.haskforce.psi.HaskellTypes.WHERE;
import static com.haskforce.psi.HaskellTypes.PRAGMA;
import static com.haskforce.psi.HaskellTypes.EQUALS;
import static com.haskforce.psi.HaskellTypes.IMPORT;
import static com.haskforce.psi.HaskellTypes.QUALIFIED;
import static com.haskforce.psi.HaskellTypes.HIDING;
import static com.haskforce.psi.HaskellTypes.PERIOD;
import static com.haskforce.psi.HaskellTypes.RPAREN;
import static com.haskforce.psi.HaskellTypes.LPAREN;
import static com.haskforce.psi.HaskellTypes.RBRACKET;
import static com.haskforce.psi.HaskellTypes.LBRACKET;
import static com.haskforce.psi.HaskellTypes.AS;
import static com.haskforce.psi.HaskellTypes.TYPE;
import static com.haskforce.psi.HaskellTypes.DATA;
import static com.haskforce.psi.HaskellTypes.IN;
import static com.haskforce.psi.HaskellTypes.DOUBLECOLON;
import static com.haskforce.psi.HaskellTypes.COMMA;
import static com.haskforce.psi.HaskellTypes.RIGHTARROW;
import static com.haskforce.psi.HaskellTypes.MINUS;
import static com.haskforce.psi.HaskellTypes.DO;
import static com.haskforce.psi.HaskellTypes.BACKSLASH;
import static com.haskforce.psi.HaskellTypes.HASH;
import static com.haskforce.psi.HaskellTypes.FOREIGN;
import static com.haskforce.psi.HaskellTypes.EXPORTTOKEN;
import static com.haskforce.psi.HaskellTypes.DOUBLEARROW;
import static com.haskforce.psi.HaskellTypes.BACKTICK;
import static com.haskforce.psi.HaskellTypes.INSTANCE;
import static com.haskforce.psi.HaskellTypes.LBRACE;
import static com.haskforce.psi.HaskellTypes.RBRACE;
import static com.haskforce.psi.HaskellTypes.EXLAMATION; // FIXME: Rename.
import static com.haskforce.psi.HaskellTypes.PIPE;

/**
 * New Parser using parser-helper.
 */
public class HaskellParser2 implements PsiParser {
    private static final Logger LOG = Logger.getInstance(HaskellParser2.class);
    private final Project myProject;
    private final JsonParser myJsonParser;

    public HaskellParser2(@NotNull Project project) {
        myProject = project;
        myJsonParser = new JsonParser(project);
    }

    @NotNull
    @Override
    public ASTNode parse(IElementType root, PsiBuilder builder) {
        PsiBuilder.Marker rootMarker = builder.mark();
        TopPair tp = myJsonParser.parse(builder.getOriginalText());
        if (tp.error != null && !tp.error.isEmpty()) {
            // TODO: Parse failed. Possibly warn. Could be annoying.
        }

        IElementType e = builder.getTokenType();
        while (!builder.eof() && (isInterruption(e) && e != OPENPRAGMA)) {
            if (e == COMMENT || e == OPENCOM) {
                parseComment(e, builder, tp.comments);
                e = builder.getTokenType();
            } else if (e == CPPIF || e == CPPELSE || e == CPPENDIF) {
                // Ignore CPP-tokens, they are not fed to parser-helper anyways.
                builder.advanceLexer();
                e = builder.getTokenType();
            } else {
                throw new RuntimeException("Unexpected failure on:" + e.toString());
            }
        }
        parseModule(builder, (Module) tp.moduleType, tp.comments);
        return chewEverything(rootMarker, root, builder);
    }

    private static ASTNode chewEverything(PsiBuilder.Marker marker, IElementType e, PsiBuilder builder) {
        while (!builder.eof()) {
            builder.advanceLexer();
        }
        marker.done(e);
        ASTNode result = builder.getTreeBuilt();
        // System.out.println("Psifile:" + builder.getTreeBuilt().getPsi().getContainingFile().getName());
        return result;
    }

    /**
     * Parses a complete module.
     */
    private static void parseModule(PsiBuilder builder, Module module, Comment[] comments) {
        parseModulePragmas(builder, module == null ? null : module.modulePragmas, comments);
        parseModuleHead(builder, module == null ? null : module.moduleHeadMaybe, comments);
        parseImportDecls(builder, module == null ? null : module.importDecls, comments);
        parseBody(builder, module == null ? null : module.decls, comments);
    }

    /**
     * Parses "module NAME [modulepragmas] [exportSpecList] where".
     */
    private static void parseModuleHead(PsiBuilder builder, ModuleHead head, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (e != MODULE) return;

        PsiBuilder.Marker moduleMark = builder.mark();
        consumeToken(builder, MODULE);
        parseModuleName(builder, head == null ? null : head.moduleName, comments);
        // TODO: parseExportSpecList(builder, head.exportSpecList, comments);
        IElementType e2 = builder.getTokenType();
        while (e2 != WHERE) {
            if (e2 == OPENPRAGMA) {
                parseGenericPragma(builder, null, comments);
            } else {
                builder.advanceLexer();
            }
            e2 = builder.getTokenType();
        }
        consumeToken(builder, WHERE);
        moduleMark.done(e);
    }

    private static void parseModuleName(PsiBuilder builder, ModuleName name,  Comment[] comments) {
        builder.getTokenType(); // Need to getTokenType to advance lexer over whitespace.
        int startPos = builder.getCurrentOffset();
        IElementType e = builder.getTokenType();
        // Data.Maybe is a legal module name.
        while ((name != null &&
               (builder.getCurrentOffset() - startPos) <  name.name.length()) ||
                name == null && e != WHERE) {
            builder.remapCurrentToken(NAME);
            consumeToken(builder, NAME);
            e = builder.getTokenType();
            if (e == PERIOD) builder.advanceLexer();
        }
    }

    /**
     * Parses a list of import statements.
     */
    private static void parseImportDecls(PsiBuilder builder, ImportDecl[] importDecls, Comment[] comments) {
        IElementType e = builder.getTokenType();

        int i = 0;
        while (isInterruption(e) ||
                importDecls != null && i < importDecls.length) {
            if (e == CPPIF || e == CPPELSE || e == CPPENDIF) {
                builder.advanceLexer();
                e = builder.getTokenType();
                continue;
            } else if (e == OPENCOM) {
                parseComment(e, builder, comments);
                e = builder.getTokenType();
                continue;
            } else if (e == OPENPRAGMA) {
                parseGenericPragma(builder, null, comments);
                e = builder.getTokenType();
                continue;
            }
            if (e != IMPORT) return;

            parseImportDecl(builder, importDecls[i], comments);
            i++;
            e = builder.getTokenType();
        }
    }

    /**
     * Returns true for elements that can occur anywhere in the tree,
     * for example comments or pragmas.
     */
    private static boolean isInterruption(IElementType e) {
        return (e == CPPIF || e == CPPELSE || e == CPPENDIF || e == OPENCOM ||
                e == OPENPRAGMA);
    }

    /**
     * Parses an import statement.
     */
    private static void parseImportDecl(PsiBuilder builder, ImportDecl importDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        PsiBuilder.Marker importMark = builder.mark();
        consumeToken(builder, IMPORT);
        IElementType e2 = builder.getTokenType();
        if (e2 == QUALIFIED || (importDecl != null && importDecl.importQualified)) {
            consumeToken(builder, QUALIFIED);
        }
        parseModuleName(builder, importDecl == null ? null : importDecl.importModule, comments);
        e2 = builder.getTokenType();
        if (e2 == AS || false) { // TODO: Update.
            consumeToken(builder, AS);
            e2 = builder.getTokenType();
            parseModuleName(builder, importDecl == null ? null : importDecl.importAs, comments);
            e2 = builder.getTokenType();
        }
        if (e2 == HIDING || false) { // (importDecl != null && importDecl.importSpecs)) { TODO: FIXME
            consumeToken(builder, HIDING);
            e2 = builder.getTokenType();
        }
        int nest = e2 == LPAREN ? 1 : 0;
        while (nest > 0) {
            builder.advanceLexer();
            e2 = builder.getTokenType();
            if (e2 == LPAREN) {
                nest++;
            } else if (e2 == RPAREN) {
                nest--;
            }
        }
        if (e2 == RPAREN) consumeToken(builder, RPAREN);
        importMark.done(e);
    }

    /**
     * Parses a foreign import statement.
     */
    private static void parseForeignImportDecl(PsiBuilder builder, ForImp importDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        consumeToken(builder, FOREIGN);
        consumeToken(builder, IMPORT);
        IElementType e2 = builder.getTokenType();
        builder.advanceLexer(); // TODO: Parse 'ccall' etc.
        e2 = builder.getTokenType();
        if (e2 != DOUBLEQUOTE) { // TODO: Parse safety.
            builder.advanceLexer();
            e2 = builder.getTokenType();
        }
        if (e2 == DOUBLEQUOTE || false) {
            parseStringLiteral(builder);
        }
        e2 = builder.getTokenType();
        parseName(builder, importDecl.name, comments);
        e2 = builder.getTokenType();
        consumeToken(builder, DOUBLECOLON);
        parseTypeTopType(builder, importDecl.type, comments);
    }

    /**
     * Parses a foreign export statement.
     */
    private static void parseForeignExportDecl(PsiBuilder builder, ForExp forExp, Comment[] comments) {
        IElementType e = builder.getTokenType();
        consumeToken(builder, FOREIGN);
        e = builder.getTokenType();
        consumeToken(builder, EXPORTTOKEN);
        IElementType e2 = builder.getTokenType();
        builder.advanceLexer(); // TODO: Parse 'ccall' etc.
        e2 = builder.getTokenType();
        if (e2 == DOUBLEQUOTE || false) {
            parseStringLiteral(builder);
        }
        e2 = builder.getTokenType();
        parseName(builder, forExp.name, comments);
        e2 = builder.getTokenType();
        consumeToken(builder, DOUBLECOLON);
        parseTypeTopType(builder, forExp.type, comments);
    }

    private static void parseBody(PsiBuilder builder, DeclTopType[] decls, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (isInterruption(e) ||
                decls != null && i < decls.length) {
            if (e == CPPIF || e == CPPELSE || e == CPPENDIF) {
                builder.advanceLexer();
                e = builder.getTokenType();
                continue;
            } else if (e == OPENCOM) {
                parseComment(e, builder, comments);
                e = builder.getTokenType();
                continue;
            } else if (e == OPENPRAGMA) {
                parseGenericPragma(builder, null, comments);
                e = builder.getTokenType();
                continue;
            }

            parseDecl(builder, decls[i], comments);
            e = builder.getTokenType();
            i++;
        }
    }

    /**
     * Parse a list of declarations.
     */
    private static void parseDecls(PsiBuilder builder, DeclTopType[] decl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (decl != null && i < decl.length) {
            parseDecl(builder, decl[i], comments);
            i++;
            e = builder.getTokenType();
        }
    }


    /**
     * Parse a single declaration.
     */
    private static void parseDecl(PsiBuilder builder, DeclTopType decl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        // Pragmas are handled by the outer loop in parseBody, so they are no-ops.
        if (decl instanceof PatBind) {
            PsiBuilder.Marker declMark = builder.mark();
            parsePatBind(builder, (PatBind) decl, comments);
            declMark.done(e);
        } else if (decl instanceof FunBind) {
            PsiBuilder.Marker declMark = builder.mark();
            parseFunBind(builder, (FunBind) decl, comments);
            declMark.done(e);
        } else if (decl instanceof DataDecl) {
            PsiBuilder.Marker declMark = builder.mark();
            parseDataDecl(builder, (DataDecl) decl, comments);
            declMark.done(e);
        } else if (decl instanceof TypeDecl) {
            PsiBuilder.Marker declMark = builder.mark();
            parseTypeDecl(builder, (TypeDecl) decl, comments);
            declMark.done(e);
        } else if (decl instanceof DataInsDecl) {
            PsiBuilder.Marker declMark = builder.mark();
            parseDataInstanceDecl(builder, (DataInsDecl) decl, comments);
            declMark.done(e);
        } else if (decl instanceof SpliceDecl) {
            PsiBuilder.Marker declMark = builder.mark();
            parseExpTopType(builder, ((SpliceDecl) decl).exp, comments);
            declMark.done(e);
        } else if (decl instanceof TypeSig) {
            PsiBuilder.Marker declMark = builder.mark();
            parseTypeSig(builder, (TypeSig) decl, comments);
            declMark.done(e);
        } else if (decl instanceof ForImp) {
            PsiBuilder.Marker declMark = builder.mark();
            parseForeignImportDecl(builder, (ForImp) decl, comments);
            declMark.done(e);
        } else if (decl instanceof ForExp) {
            PsiBuilder.Marker declMark = builder.mark();
            parseForeignExportDecl(builder, (ForExp) decl, comments);
            declMark.done(e);
        } else if (decl instanceof InlineSig) {
            // parseGenericPragma(builder, (InlineSig) decl, comments);
        } else if (decl instanceof InlineConlikeSig) {
            // parseGenericPragma(builder, (InlineConlikeSig) decl, comments);
        } else if (decl instanceof SpecSig) {
            // parseGenericPragma(builder, (SpecSig) decl, comments);
        } else if (decl instanceof SpecInlineSig) {
            // parseGenericPragma(builder, (SpecSig) decl, comments);
        } else if (decl instanceof RulePragmaDecl) {
            // parseGenericPragma(builder, (SpecSig) decl, comments);
        } else if (decl instanceof DeprPragmaDecl) {
            //  parseGenericPragma(builder, (DeprPragmaDecl) decl, comments);
        } else if (decl instanceof WarnPragmaDecl) {
            // parseGenericPragma(builder, (WarnPragmaDecl) decl, comments);
        } else if (decl instanceof AnnPragma) {
            // parseGenericPragma(builder, (AnnPragma) decl, comments);
        } else {
            throw new RuntimeException("Unexpected decl type: " + decl.toString());
        }
    }

    private static void parsePatBind(PsiBuilder builder, PatBind patBind, Comment[] comments) {
        IElementType e = builder.getTokenType();
        parsePatTopType(builder, patBind.pat, comments);
        if (patBind.type != null) throw new RuntimeException("Unexpected type in patbind");
        // TODO: parseType(builder, patBind.type, comments);
        parseRhs(builder, patBind.rhs, comments);
        if (patBind.binds != null) throw new RuntimeException("Unexpected binds in patbind");
    }

    private static void parseFunBind(PsiBuilder builder, FunBind funBind, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (funBind.match != null && i < funBind.match.length) {
            parseMatchTop(builder, funBind.match[i], comments);
            i++;
        }
    }

    /**
     * Parses a data declaration.
     */
    private static void parseDataDecl(PsiBuilder builder, DataDecl dataDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        consumeToken(builder, DATA);
        parseDeclHead(builder, dataDecl.declHead, comments);
        e = builder.getTokenType();
        if (e == EQUALS) consumeToken(builder, EQUALS);
        int i = 0;
        e = builder.getTokenType();
        while (dataDecl.qualConDecls != null && i < dataDecl.qualConDecls.length) {
            parseQualConDecl(builder, dataDecl.qualConDecls[i], comments);
            i++;
            if (i < dataDecl.qualConDecls.length) {
                builder.advanceLexer();
                e = builder.getTokenType();
            }
        }
    }

    /**
     * Parses a data instance declaration.
     */
    private static void parseDataInstanceDecl(PsiBuilder builder, DataInsDecl dataDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        consumeToken(builder, DATA);
        e = builder.getTokenType();
        consumeToken(builder, INSTANCE);
        e = builder.getTokenType();
        parseTypeTopType(builder, dataDecl.type, comments);
        e = builder.getTokenType();
        if (e == EQUALS) consumeToken(builder, EQUALS);
        int i = 0;
        e = builder.getTokenType();
        while (dataDecl.qualConDecls != null && i < dataDecl.qualConDecls.length) {
            parseQualConDecl(builder, dataDecl.qualConDecls[i], comments);
            i++;
            if (i < dataDecl.qualConDecls.length) {
                builder.advanceLexer();
                e = builder.getTokenType();
            }
        }
        e = builder.getTokenType();
        if (dataDecl.derivingMaybe != null) throw new RuntimeException("TODO: deriving unimplemeted");
    }

    /**
     * Parses the left side of '=' in a data/type declaration.
     */
    private static void parseDeclHead(PsiBuilder builder, DeclHeadTopType declHead, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (declHead instanceof DHead) {
            parseName(builder, ((DHead) declHead).name, comments);
            e = builder.getTokenType();
            parseTyVarBinds(builder, ((DHead) declHead).tyVars, comments);
        } else if (declHead instanceof DHInfix) {
            throw new RuntimeException("DHInfix:" + declHead.toString());
        } else if (declHead instanceof DHParen) {
            throw new RuntimeException("DHParen:" + declHead.toString());
        }
    }

    /**
     * Parses the type variables in a data declaration.
     */
    private static void parseTyVarBinds(PsiBuilder builder, TyVarBindTopType[] tyVarBindTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (tyVarBindTopType != null && i < tyVarBindTopType.length) {
            parseTyVarBind(builder, tyVarBindTopType[i], comments);
            i++;
        }
        e = builder.getTokenType();
    }

    /**
     * Parses the type variables in a data declaration.
     */
    private static void parseTyVarBind(PsiBuilder builder, TyVarBindTopType tyVarBindTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();

        if (tyVarBindTopType instanceof KindedVar) {
            parseName(builder, ((KindedVar) tyVarBindTopType).name, comments);
            throw new RuntimeException("TODO: Implement parseKindVar()");
        } else if (tyVarBindTopType instanceof UnkindedVar) {
            parseName(builder, ((UnkindedVar) tyVarBindTopType).name, comments);
        }
        e = builder.getTokenType();
    }

    /**
     * Parses a type declaration.
     */
    private static void parseTypeDecl(PsiBuilder builder, TypeDecl typeDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        consumeToken(builder, TYPE);
        parseDeclHead(builder, typeDecl.declHead, comments);
        e = builder.getTokenType();
        if (e == EQUALS) consumeToken(builder, EQUALS);
        parseTypeTopType(builder, typeDecl.type, comments);
        e = builder.getTokenType();
    }

    /**
     * Parses a type signature.
     */
    private static void parseTypeSig(PsiBuilder builder, TypeSig dataDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        parseNames(builder, dataDecl.names, comments);
        e = builder.getTokenType();
        consumeToken(builder, DOUBLECOLON);
        e = builder.getTokenType();
        parseTypeTopType(builder, dataDecl.type, comments);
    }

    /**
     * Parses a qualified constructor declaration.
     */
    private static void parseQualConDecl(PsiBuilder builder, QualConDecl qualConDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        parseConDecl(builder, qualConDecl == null ? null : qualConDecl.conDecl, comments);
    }

    /**
     * Parses a constructor declaration.
     */
    private static void parseConDecl(PsiBuilder builder,  ConDeclTopType conDecl, Comment[] comments) {
        if (conDecl instanceof ConDecl) {
            parseName(builder, ((ConDecl) conDecl).name, comments);
            IElementType e = builder.getTokenType();
            parseBangTypes(builder, conDecl == null ? null : ((ConDecl) conDecl).bangTypes, comments);
        } else if (conDecl instanceof InfixConDecl) {
            IElementType e = builder.getTokenType();
            parseBangType(builder, ((InfixConDecl) conDecl).b1, comments);
            e = builder.getTokenType();
            parseName(builder, ((InfixConDecl) conDecl).name, comments);
            parseBangType(builder, ((InfixConDecl) conDecl).b2, comments);
        } else if (conDecl instanceof RecDecl) {
            parseName(builder, ((RecDecl) conDecl).name, comments);
            boolean layouted = false;
            IElementType e = builder.getTokenType();
            if (e == LBRACE) {
                consumeToken(builder, LBRACE);
                e = builder.getTokenType();
                layouted = true;
            }
            parseFieldDecls(builder, ((RecDecl) conDecl).fields, comments);
            e = builder.getTokenType();
            if (layouted) {
                consumeToken(builder, RBRACE);
                e = builder.getTokenType();
            }
        }
    }

    /**
     * Parses the field declarations in a GADT-style declaration.
     */
    private static void parseFieldDecls(PsiBuilder builder, FieldDecl[] fieldDecls, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (fieldDecls != null && i < fieldDecls.length) {
            parseFieldDecl(builder, fieldDecls[i], comments);
            i++;
        }
        e = builder.getTokenType();
    }

    /**
     * Parses a field declaration.
     */
    private static void parseFieldDecl(PsiBuilder builder,  FieldDecl fieldDecl, Comment[] comments) {
        IElementType e = builder.getTokenType();
        parseNames(builder, fieldDecl.names, comments);
        e = builder.getTokenType();
        consumeToken(builder, DOUBLECOLON);
        e = builder.getTokenType();
        parseBangType(builder, fieldDecl.bang, comments);
        e = builder.getTokenType();
    }

    /**
     * Parses a list of bang types.
     */
    private static void parseBangTypes(PsiBuilder builder,  BangTypeTopType[] bangTypes, Comment[] comments) {
        int i = 0;
        while (bangTypes != null && i < bangTypes.length) {
            parseBangType(builder, bangTypes[i], comments);
            i++;
        }
    }

    /**
     * Parses one bang type.
     */
    private static void parseBangType(PsiBuilder builder,  BangTypeTopType bangType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        // TODO: Refine bangType.
        if (bangType instanceof UnBangedTy) {
            parseTypeTopType(builder, ((UnBangedTy) bangType).type, comments);
        } else if (bangType instanceof BangedTy) {
            consumeToken(builder, EXLAMATION);
            parseTypeTopType(builder, ((BangedTy) bangType).type, comments);
            e = builder.getTokenType();
        } else if (bangType instanceof UnpackedTy) {
            parseGenericPragma(builder, null, comments);
            consumeToken(builder, EXLAMATION);
            e = builder.getTokenType();
            parseTypeTopType(builder, ((UnpackedTy) bangType).type, comments);
            e = builder.getTokenType();
        }
    }

    private static void parseMatchTop(PsiBuilder builder, MatchTopType matchTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (matchTopType instanceof Match) {
            parseMatch(builder, (Match) matchTopType, comments);
        } else if (matchTopType instanceof InfixMatch) {
            //TODO: parseInfixMatch(builder, (InfixMatch) matchTopType, comments);
            throw new RuntimeException("infixmatch");
        }
    }

    private static void parseMatch(PsiBuilder builder, Match match, Comment[] comments) {
        IElementType e = builder.getTokenType();
        parseName(builder, match.name, comments);
        int i = 0;
        while (match.pats != null && i < match.pats.length) {
            parsePatTopType(builder, match.pats[i], comments);
            i++;
        }
        parseRhs(builder, match.rhs, comments);
        e = builder.getTokenType();
        if (e == WHERE) {
            consumeToken(builder, WHERE);
            parseBindsTopType(builder, match.bindsMaybe, comments);
            e = builder.getTokenType();
        }
    }

    /**
     * Parses one binding.
     */
    private static void parseBindsTopType(PsiBuilder builder, BindsTopType bindsTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (bindsTopType instanceof BDecls) {
            parseDecls(builder, ((BDecls) bindsTopType).decls, comments);
        } else if (bindsTopType instanceof IPBinds) {
            throw new RuntimeException("TODO: Implement IPBinds:" + bindsTopType.toString());
        }
    }

    /**
     * Parses several patterns.
     */
    private static void parsePatTopTypes(PsiBuilder builder, PatTopType[] pats,  Comment[] comments) {
        int i = 0;
        while(pats != null && i < pats.length) {
            parsePatTopType(builder, pats[i], comments);
            i++;
        }
    }

    /**
     * Parses one pattern.
     */
    private static void parsePatTopType(PsiBuilder builder, PatTopType patTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (patTopType instanceof PVar) {
            parsePVar(builder, (PVar) patTopType, comments);
        } else if (patTopType instanceof PLit) {
            parseLiteralTop(builder, ((PLit) patTopType).lit, comments);
            e = builder.getTokenType();
        } else if (patTopType instanceof PList) {
            consumeToken(builder, LBRACKET);
            parsePatTopTypes(builder, ((PList) patTopType).pats, comments);
            e = builder.getTokenType();
            consumeToken(builder, RBRACKET);
            e = builder.getTokenType();
        } else if (patTopType instanceof PParen) {
            consumeToken(builder, LPAREN);
            e = builder.getTokenType();
            parsePatTopType(builder, ((PParen) patTopType).pat, comments);
            consumeToken(builder, RPAREN);
            e = builder.getTokenType();
        } else if (patTopType instanceof PRec) {
            parseQName(builder, ((PRec) patTopType).qName, comments);
            e = builder.getTokenType();
            throw new RuntimeException("TODO: parsePatFields");
        } else {
            throw new RuntimeException("parsePatTopType" + patTopType.toString());
        }
    }

    private static void parseComment(IElementType start, PsiBuilder builder, Comment[] comments) {
        PsiBuilder.Marker startCom = builder.mark();
        IElementType e = builder.getTokenType();
        while (e == COMMENT || e == COMMENTTEXT ||
                e == OPENCOM || e == CLOSECOM) {
            builder.advanceLexer();
            e = builder.getTokenType();
        }
        startCom.done(start);
    }

    /**
     * Parses a group of module pragmas.
     */
    private static void parseModulePragmas(PsiBuilder builder, ModulePragmaTopType[] modulePragmas,  Comment[] comments) {
        int i = 0;
        while(modulePragmas != null && i < modulePragmas.length) {
            parseModulePragma(builder, modulePragmas[i], comments);
            i++;
        }
    }

    /**
     * Parses a module pragma.
     */
    private static void parseModulePragma(PsiBuilder builder, ModulePragmaTopType modulePragmaTopType,  Comment[] comments) {
        int i = 0;
        if (modulePragmaTopType instanceof LanguagePragma) {
            LanguagePragma langPragma = (LanguagePragma) modulePragmaTopType;
            IElementType e = builder.getTokenType();
            PsiBuilder.Marker pragmaMark = builder.mark();
            consumeToken(builder, OPENPRAGMA);
            consumeToken(builder, PRAGMA);
            while (langPragma.names != null && i < langPragma.names.length) {
                // TODO: Improve precision of pragma lexing.
                // parseName(builder, langPragma.names[i], comments);
                i++;
            }
            consumeToken(builder, CLOSEPRAGMA);
            pragmaMark.done(e);
        } else if (modulePragmaTopType instanceof OptionsPragma) {
            // FIXME: Use optionsPragma information.
            OptionsPragma optionsPragma = (OptionsPragma) modulePragmaTopType;
            IElementType e = builder.getTokenType();
            PsiBuilder.Marker pragmaMark = builder.mark();
            chewPragma(builder);
            consumeToken(builder, CLOSEPRAGMA);
            pragmaMark.done(e);
        } else if (modulePragmaTopType instanceof AnnModulePragma) {
            // FIXME: Use annModulePragma information.
            AnnModulePragma annModulePragma = (AnnModulePragma) modulePragmaTopType;
            IElementType e = builder.getTokenType();
            PsiBuilder.Marker pragmaMark = builder.mark();
            chewPragma(builder);
            consumeToken(builder, CLOSEPRAGMA);
            pragmaMark.done(e);
        }
    }

    /**
     * Parses a pattern variable.
     */
    private static void parsePVar(PsiBuilder builder, PVar pVar,  Comment[] comments) {
        builder.remapCurrentToken(VARID); // FIXME: Should be PVARID
        consumeToken(builder, VARID);
    }

    private static void parseRhs(PsiBuilder builder, RhsTopType rhsTopType,  Comment[] comments) {
        consumeToken(builder, EQUALS);
        if (rhsTopType instanceof UnGuardedRhs) {
            parseExpTopType(builder, ((UnGuardedRhs) rhsTopType).exp, comments);
        } else if (rhsTopType instanceof GuardedRhss) {
            throw new RuntimeException("GuardedRhss" + rhsTopType.toString());
        }
    }

    /**
     * Parses a qualified name.
     */
    private static void parseQOp(PsiBuilder builder, QOpTopType qOpTopType,  Comment[] comments) {
        IElementType e = builder.getTokenType();
        boolean backticked = false;
        if (e == BACKTICK) {
            backticked = true;
            consumeToken(builder, BACKTICK);
            e = builder.getTokenType();
        }
        if (qOpTopType instanceof QVarOp) {
            parseQName(builder, ((QVarOp) qOpTopType).qName, comments);
        } else if (qOpTopType instanceof QConOp) {
            parseQName(builder, ((QConOp) qOpTopType).qName, comments);
        }
        if (backticked) consumeToken(builder, BACKTICK);
        e = builder.getTokenType();
    }

    /**
     * Parses a qualified name.
     */
    private static void parseQName(PsiBuilder builder, QNameTopType qNameTopType,  Comment[] comments) {
        if (qNameTopType instanceof Qual) {
            Qual name = (Qual) qNameTopType;
            parseModuleName(builder, name.moduleName, comments);
            parseName(builder, name.name, comments);
        } else if (qNameTopType instanceof UnQual) {
            parseName(builder, ((UnQual) qNameTopType).name, comments);
        } else if (qNameTopType instanceof Special) {
            parseSpecialConTopType(builder, ((Special) qNameTopType).specialCon, comments);
        }
    }

    /**
     * Parses a special constructor.
     */
    private static void parseSpecialConTopType(PsiBuilder builder, SpecialConTopType specialConTopType,  Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (specialConTopType instanceof UnitCon) {
            consumeToken(builder, LPAREN);
            e = builder.getTokenType();
            consumeToken(builder, RPAREN);
            e = builder.getTokenType();
        } else if (specialConTopType instanceof ListCon) {
            consumeToken(builder, LBRACKET);
            e = builder.getTokenType();
            consumeToken(builder, RBRACKET);
            e = builder.getTokenType();
        } else if (specialConTopType instanceof FunCon) {
            consumeToken(builder, LPAREN);
            e = builder.getTokenType();
            consumeToken(builder, RIGHTARROW);
            e = builder.getTokenType();
            consumeToken(builder, RPAREN);
            e = builder.getTokenType();
        } else if (specialConTopType instanceof TupleCon) {
            throw new RuntimeException("TODO: implement TupleCon");
        } else if (specialConTopType instanceof Cons) {
            throw new RuntimeException("TODO: implement Cons");
        } else if (specialConTopType instanceof UnboxedSingleCon) {
            consumeToken(builder, LPAREN);
            e = builder.getTokenType();
            consumeToken(builder, HASH);
            e = builder.getTokenType();
            consumeToken(builder, HASH);
            e = builder.getTokenType();
            consumeToken(builder, RPAREN);
        }
    }

    /**
     * Parses a list of names.
     */
    private static void parseNames(PsiBuilder builder,  NameTopType[] names, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (names != null && i < names.length) {
            parseName(builder, names[i], comments);
            i++;
            e = builder.getTokenType();
            if (e == COMMA) consumeToken(builder, COMMA);
        }
    }

    /**
     * Parses a name.
     */
    private static void parseName(PsiBuilder builder, NameTopType nameTopType,  Comment[] comments) {
        if (nameTopType instanceof Ident) {
            builder.remapCurrentToken(NAME);
            consumeToken(builder, NAME);
        } else if (nameTopType instanceof Symbol) {
            IElementType e = builder.getTokenType();
            builder.remapCurrentToken(SYMBOL);
            consumeToken(builder, SYMBOL);
        }
    }

    /**
     * Parses a literal
     */
    private static void parseLiteralTop(PsiBuilder builder, LiteralTopType literalTopType,  Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (literalTopType instanceof StringLit) {
            parseStringLiteral(builder);
        } else if (literalTopType instanceof IntLit) {
            builder.advanceLexer();
            e = builder.getTokenType();
        } else {
            throw new RuntimeException("LiteralTop: " + literalTopType.toString());
        }
    }

    /**
     * Parse a string literal.
     */
    private static void parseStringLiteral(PsiBuilder builder) {
        IElementType e = builder.getTokenType();
        PsiBuilder.Marker marker = builder.mark();
        consumeToken(builder, DOUBLEQUOTE);
        IElementType e2 = builder.getTokenType();
        while (e2 != DOUBLEQUOTE) {
            if (e2 == BADSTRINGTOKEN) {
                builder.error("Bad stringtoken");
                builder.advanceLexer();
            } else {
                consumeToken(builder, STRINGTOKEN);
            }
            e2 = builder.getTokenType();
        }
        consumeToken(builder, DOUBLEQUOTE);
        marker.done(e);
    }

    /**
     * Parses a list of statements.
     */
    private static void parseStmtTopTypes(PsiBuilder builder, StmtTopType[] stmtTopTypes, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (stmtTopTypes != null && i < stmtTopTypes.length) {
            parseStmtTopType(builder, stmtTopTypes[i], comments);
            i++;
        }
    }

    /**
     * Parses a statement.
     */
    private static void parseStmtTopType(PsiBuilder builder, StmtTopType stmtTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (stmtTopType instanceof Generator) {
            parsePatTopType(builder, ((Generator) stmtTopType).pat, comments);
            parseExpTopType(builder, ((Generator) stmtTopType).exp, comments);
        } else if (stmtTopType instanceof Qualifier) {
            parseExpTopType(builder, ((Qualifier) stmtTopType).exp, comments);
        } else if (stmtTopType instanceof LetStmt) {
            throw new RuntimeException("TODO: Implement parseBinds()");
        } else if (stmtTopType instanceof RecStmt) {
            builder.advanceLexer();
            e = builder.getTokenType();
            parseStmtTopTypes(builder, ((RecStmt) stmtTopType).stmts, comments);
            e = builder.getTokenType();
        }
    }

    /**
     * Parses a list of expressions.
     */
    private static void parseExpTopTypes(PsiBuilder builder, ExpTopType[] expTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (expTopType != null && i < expTopType.length) {
            parseExpTopType(builder, expTopType[i], comments);
            i++;
            e = builder.getTokenType();
            if (e == COMMA) consumeToken(builder, COMMA);
        }
    }

    /**
     * Parses an expression.
     */
    private static void parseExpTopType(PsiBuilder builder, ExpTopType expTopType, Comment[] comments) {
        IElementType e1 = builder.getTokenType();
        if (expTopType instanceof App) {
            parseExpTopType(builder, ((App) expTopType).e1, comments);
            parseExpTopType(builder, ((App) expTopType).e2, comments);
        } else if (expTopType instanceof Var) {
            parseQName(builder, ((Var) expTopType).qName, comments);
        } else if (expTopType instanceof Con) {
            parseQName(builder, ((Con) expTopType).qName, comments);
        } else if (expTopType instanceof Lit) {
            parseLiteralTop(builder, ((Lit) expTopType).literal, comments);
        } else if (expTopType instanceof InfixApp) {
            parseExpTopType(builder, ((InfixApp) expTopType).e1, comments);
            IElementType e = builder.getTokenType();
            builder.advanceLexer();
            e = builder.getTokenType();
            parseExpTopType(builder, ((InfixApp) expTopType).e2, comments);
            e = builder.getTokenType();
        } else if (expTopType instanceof List) {
            builder.advanceLexer();
            parseExpTopTypes(builder, ((List) expTopType).exps, comments);
            IElementType e = builder.getTokenType();
            builder.advanceLexer();
        } else if (expTopType instanceof NegApp) {
            consumeToken(builder, MINUS);
            parseExpTopType(builder, ((NegApp) expTopType).e1, comments);
        } else if (expTopType instanceof Do) {
            consumeToken(builder, DO);
            parseStmtTopTypes(builder, ((Do) expTopType).stmts, comments);
        } else if (expTopType instanceof Lambda) {
            consumeToken(builder, BACKSLASH);
            IElementType e = builder.getTokenType();
            parsePatTopTypes(builder, ((Lambda) expTopType).pats, comments);
            e = builder.getTokenType();
            consumeToken(builder, RIGHTARROW);
            parseExpTopType(builder, ((Lambda) expTopType).exp, comments);
            e = builder.getTokenType();
        }  else if (expTopType instanceof Tuple) {
            consumeToken(builder, LPAREN);
            IElementType e = builder.getTokenType();
            boolean unboxed = parseBoxed(builder, ((Tuple) expTopType).boxed, comments);
            e = builder.getTokenType();
            parseExpTopTypes(builder, ((Tuple) expTopType).exps, comments);
            e = builder.getTokenType();
            if (unboxed) {
                consumeToken(builder, HASH);
                e = builder.getTokenType();
            }
            consumeToken(builder, RPAREN);
            e1 = builder.getTokenType();
        } else if (expTopType instanceof Paren) {
            consumeToken(builder, LPAREN);
            e1 = builder.getTokenType();
            parseExpTopType(builder, ((Paren) expTopType).exp, comments);
            e1 = builder.getTokenType();
            consumeToken(builder, RPAREN);
            e1 = builder.getTokenType();
        } else if (expTopType instanceof RightSection) {
            e1 = builder.getTokenType();
            consumeToken(builder, LPAREN);
            parseQOp(builder, ((RightSection) expTopType).qop, comments);
            parseExpTopType(builder, ((RightSection) expTopType).exp, comments);
            e1 = builder.getTokenType();
            consumeToken(builder, RPAREN);
            e1 = builder.getTokenType();
        } else if (expTopType instanceof Let) {
            builder.advanceLexer();
            IElementType e = builder.getTokenType();
            // TODO: parseBinds(builder, ((Let) expTopType).binds, comments);
            while (e != IN) {
                builder.advanceLexer();
                e = builder.getTokenType();
            }
            consumeToken(builder, IN);
            parseExpTopType(builder, ((Let) expTopType).exp, comments);
        } else if (expTopType instanceof QuasiQuote) {
            IElementType e = builder.getTokenType();
            consumeToken(builder, LBRACKET);
            builder.advanceLexer();
            e = builder.getTokenType();
            consumeToken(builder, PIPE);
            e = builder.getTokenType();
            while (e != PIPE) {
                builder.advanceLexer();
                e = builder.getTokenType();
            }
            consumeToken(builder, PIPE);
            consumeToken(builder, RBRACKET);
            e = builder.getTokenType();
        } else if (expTopType instanceof CorePragma) {
            parseGenericPragma(builder, null, comments);
            parseExpTopType(builder, ((CorePragma) expTopType).exp, comments);
        }  else if (expTopType instanceof Proc) {
            e1 = builder.getTokenType();
            builder.advanceLexer(); // TODO: consumeToken(builder, PROCTOKEN);
            e1 = builder.getTokenType();
            parsePatTopType(builder, ((Proc) expTopType).pat, comments);
            consumeToken(builder, RIGHTARROW);
            parseExpTopType(builder, ((Proc) expTopType).exp, comments);
        } else {
            throw new RuntimeException("parseExpTopType: " + expTopType.toString());
        }
    }

    /**
     * Parses a list of types.
     */
    private static void parseTypeTopTypes(PsiBuilder builder, TypeTopType[] typeTopTypes, Comment[] comments) {
        IElementType e = builder.getTokenType();
        int i = 0;
        while (typeTopTypes != null && i < typeTopTypes.length) {
            parseTypeTopType(builder, typeTopTypes[i], comments);
            i++;
            e = builder.getTokenType();
            if (e == COMMA) consumeToken(builder, COMMA);
        }
    }

    /**
     * Parses a type.
     */
    private static void parseTypeTopType(PsiBuilder builder, TypeTopType typeTopType, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (typeTopType instanceof TyForall) { // FIXME: No forall lexeme.
            TyForall t = (TyForall) typeTopType;
            e = builder.getTokenType();
            if (t.tyVarBinds != null) { // Implicit foralls for typeclasses.
                builder.advanceLexer();
                e = builder.getTokenType();
                parseTyVarBinds(builder, t.tyVarBinds, comments);
                e = builder.getTokenType();
                consumeToken(builder, PERIOD);
            }
            parseContextTopType(builder, t.context, comments);
            e = builder.getTokenType();
            if (e == DOUBLEARROW) consumeToken(builder, DOUBLEARROW);
            parseTypeTopType(builder, t.type, comments);
            e = builder.getTokenType();
        } else if (typeTopType instanceof TyFun) {
            parseTypeTopType(builder, ((TyFun) typeTopType).t1, comments);
            consumeToken(builder, RIGHTARROW);
            parseTypeTopType(builder, ((TyFun) typeTopType).t2, comments);
        } else if (typeTopType instanceof TyTuple) {
            consumeToken(builder, LPAREN);
            e = builder.getTokenType();
            boolean unboxed = parseBoxed(builder, ((TyTuple) typeTopType).boxed, comments);
            e = builder.getTokenType();
            parseTypeTopTypes(builder, ((TyTuple) typeTopType).types, comments);
            e = builder.getTokenType();
            if (unboxed) {
                consumeToken(builder, HASH);
                e = builder.getTokenType();
            }
            consumeToken(builder, RPAREN);
            e = builder.getTokenType();
        } else if (typeTopType instanceof TyList) {
            consumeToken(builder, LBRACKET);
            e = builder.getTokenType();
            parseTypeTopType(builder, ((TyList) typeTopType).t, comments);
            e = builder.getTokenType();
            consumeToken(builder, RBRACKET);
            e = builder.getTokenType();
        } else if (typeTopType instanceof TyApp) {
            parseTypeTopType(builder, ((TyApp) typeTopType).t1, comments);
            e = builder.getTokenType();
            parseTypeTopType(builder, ((TyApp) typeTopType).t2, comments);
            e = builder.getTokenType();
        } else if (typeTopType instanceof TyVar) {
            parseName(builder, ((TyVar) typeTopType).name, comments);
            e = builder.getTokenType();
        } else if (typeTopType instanceof TyCon) {
            parseQName(builder, ((TyCon) typeTopType).qName, comments);
        } else if (typeTopType instanceof TyParen) {
            consumeToken(builder, LPAREN);
            e = builder.getTokenType();
            parseTypeTopType(builder, ((TyParen) typeTopType).type, comments);
            e = builder.getTokenType();
            consumeToken(builder, RPAREN);
            e = builder.getTokenType();
        } else if (typeTopType instanceof TyInfix) {
            e = builder.getTokenType();
            parseTypeTopType(builder, ((TyInfix) typeTopType).t1, comments);
            e = builder.getTokenType();
            parseQName(builder, ((TyInfix) typeTopType).qName, comments);
            e = builder.getTokenType();
            parseTypeTopType(builder, ((TyInfix) typeTopType).t2, comments);
            e = builder.getTokenType();
        } else {
            throw new RuntimeException("parseTypeTopType: " + typeTopType.toString());
        }
    }

    /**
     * Parses contexts.
     */
    private static void parseContextTopType(PsiBuilder builder, ContextTopType context, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (context instanceof CxSingle) {
            parseAsstTopType(builder, ((CxSingle) context).asst, comments);
        } else if (context instanceof CxTuple) {
            throw new RuntimeException("TODO: Implement CxTuple");
        } else if (context instanceof CxParen) {
            consumeToken(builder, LPAREN);
            parseContextTopType(builder, ((CxParen) context).context, comments);
            e = builder.getTokenType();
            consumeToken(builder, RPAREN);
            e = builder.getTokenType();
        } else if (context instanceof CxEmpty) {
            throw new RuntimeException("TODO: Implement CxEmpty");
        }
    }

    /**
     * Parses contexts.
     */
    private static void parseAsstTopType(PsiBuilder builder, AsstTopType asst, Comment[] comments) {
        IElementType e = builder.getTokenType();
        if (asst instanceof ClassA) {
            parseQName(builder, ((ClassA) asst).qName, comments);
            e = builder.getTokenType();
            parseTypeTopTypes(builder, ((ClassA) asst).types, comments);
            e = builder.getTokenType();
        } else if (asst instanceof InfixA) {
            throw new RuntimeException("TODO: Parse InfixA");
        } else if (asst instanceof IParam) {
            throw new RuntimeException("TODO: Parse IParam");
            /* Preliminary untested implementation:
            parseContextTopType(builder, ((IParam) asst).ipName, comments);
            e = builder.getTokenType();
            parseTypeTopType(builder, ((IParam) asst).type, comments);
            e = builder.getTokenType();
            */
        } else if (asst instanceof EqualP) {
            throw new RuntimeException("TODO: Parse EqualP");
            /* Preliminary untested implementation:
            parseTypeTopType(builder, ((EqualP) asst).t1, comments);
            consumeToken(builder, TILDETOKENHERE);
            e = builder.getTokenType();
            parseTypeTopType(builder,((EqualP) asst).t2, comments);
            e = builder.getTokenType();
            */
        }
    }

    /**
     * Parses box annotations.
     */
    public static boolean parseBoxed(PsiBuilder builder,  BoxedTopType boxedTopType, Comment[] comments) { // TODO: Improve granularity.
        IElementType e = builder.getTokenType();
        if (boxedTopType instanceof Boxed) {
            return false;
        } else if (boxedTopType instanceof Unboxed) {
            builder.advanceLexer(); // '#'
            return true;
        }
        throw new RuntimeException("Unexpected boxing: " + boxedTopType.toString());
    }

    /**
     * Parses a generic pragma.
     */
    public static void parseGenericPragma(PsiBuilder builder, DeclTopType annPragma, Comment[] comments) { // TODO: Improve granularity.
        PsiBuilder.Marker pragmaMark = builder.mark();
        IElementType e = builder.getTokenType();
        chewPragma(builder);
        consumeToken(builder, CLOSEPRAGMA);
        pragmaMark.done(e);
    }

    /**
     * Eats a complete pragma and leaves the builder at CLOSEPRAGMA token.
     */
    public static void chewPragma(PsiBuilder builder) {
        IElementType e = builder.getTokenType();
        while (e != CLOSEPRAGMA) {
            builder.advanceLexer();
            e = builder.getTokenType();
        }
    }

    public static boolean consumeToken(PsiBuilder builder_, IElementType token) {
        if (nextTokenIsInner(builder_, token)) {
            builder_.advanceLexer();
            return true;
        }
        return false;
    }

    public static boolean nextTokenIsInner(PsiBuilder builder_, IElementType token) {
        IElementType tokenType = builder_.getTokenType();
        if (token != tokenType) {
            System.out.println("Unexpected token: " + tokenType + " vs " + token);
        }
        return token == tokenType;
    }
}
