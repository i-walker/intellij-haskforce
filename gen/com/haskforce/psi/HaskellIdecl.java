// This is a generated file. Not intended for manual editing.
package com.haskforce.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface HaskellIdecl extends PsiElement {

  @Nullable
  HaskellAtype getAtype();

  @Nullable
  HaskellCon getCon();

  @NotNull
  List<HaskellConstr> getConstrList();

  @NotNull
  List<HaskellCtype> getCtypeList();

  @NotNull
  List<HaskellExp> getExpList();

  @Nullable
  HaskellKind getKind();

  @Nullable
  HaskellOqtycon getOqtycon();

  @NotNull
  List<HaskellPat> getPatList();

  @NotNull
  List<HaskellPstringtoken> getPstringtokenList();

  @NotNull
  List<HaskellQcon> getQconList();

  @Nullable
  HaskellQtycls getQtycls();

  @NotNull
  List<HaskellQvar> getQvarList();

  @Nullable
  HaskellRhs getRhs();

  @Nullable
  HaskellTypee getTypee();

  @NotNull
  List<HaskellVarid> getVaridList();

  @Nullable
  HaskellVarop getVarop();

  @Nullable
  HaskellVars getVars();

  @NotNull
  List<HaskellVarsym> getVarsymList();

}
