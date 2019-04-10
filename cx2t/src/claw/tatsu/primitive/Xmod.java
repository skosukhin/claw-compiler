/*
 * This file is released under terms of BSD license
 * See LICENSE file for more information
 */
package claw.tatsu.primitive;

import claw.tatsu.common.Context;
import claw.tatsu.xcodeml.abstraction.PromotionInfo;
import claw.tatsu.xcodeml.exception.IllegalTransformationException;
import claw.tatsu.xcodeml.xnode.XnodeUtil;
import claw.tatsu.xcodeml.xnode.common.*;
import claw.tatsu.xcodeml.xnode.fortran.*;
import org.w3c.dom.Document;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Primitive transformation and test and utility for XcodeML/F and CLAW enhanced
 * module files. This includes:
 * - Locate module file and load it into FortranModule object.
 * - Locate CLAW enhanced module file and load it into FortranModule object.
 * - Format specific suffix for module writing and search.
 * - Update signature of a function type in a module file.
 *
 * @author clementval
 */
public final class Xmod {

  private static final String CLAW_MOD_SUFFIX = "claw";
  private static final String XMOD_FILE_EXTENSION = ".xmod";

  // Avoid instantiation of this class
  private Xmod() {
  }

  /**
   * Find module by name.
   *
   * @param moduleName   Name of the module.
   * @param moduleSuffix Suffix to the module name.
   * @return A FortranModule object representing the module if found.
   * Null otherwise.
   */
  private static FortranModule find(String moduleName, String moduleSuffix) {
    if(moduleSuffix == null) {
      moduleSuffix = "";
    }
    for(String dir : Context.get().getModuleCache().getSearchPaths()) {
      String path = dir + "/" + moduleName + moduleSuffix;
      File f = new File(path);
      if(f.exists()) {
        Document doc = XnodeUtil.readXmlFile(path);
        return doc != null ? new FortranModule(doc, moduleName, dir) : null;
      }
    }
    return null;
  }

  /**
   * Find module by name.
   *
   * @param moduleName Name of the module.
   * @return A FortranModule object representing the module if found.
   * Null otherwise.
   */
  public static FortranModule find(String moduleName) {
    return moduleName == null ? null : find(moduleName, XMOD_FILE_EXTENSION);
  }

  /**
   * Locate a module file generated by CLAW translator.
   *
   * @param moduleName Name of the module.
   * @return A FortranModule object representing the module if found.
   * Null otherwise.
   */
  public static FortranModule findClaw(String moduleName)
  {
    return find(moduleName, getSuffix());
  }

  /**
   * Get a formatted suffix for the CLAW module file including the directive
   * and target of the current transformation.
   * .[directive].[target].claw
   *
   * @return A formatted string for the CLAW module file name.
   */
  public static String getSuffix()
  {
    StringBuilder str = new StringBuilder();
    str.append(".");
    if(Context.get().getCompilerDirective() != null) {
      str.append(Context.get().getCompilerDirective()).append(".");
    }
    if(Context.get().getTarget() != null) {
      str.append(Context.get().getTarget()).append(".");
    }
    str.append(CLAW_MOD_SUFFIX);
    str.append(XMOD_FILE_EXTENSION);
    return str.toString();
  }

  /**
   * Update the function signature in the module file to reflects local changes.
   *
   * @param moduleName    Xmod name to update.
   * @param xcodeml       Current XcodeML file unit.
   * @param fctDef        Function definition that has been changed.
   * @param fctType       Function type that has been changed.
   * @param importFctType If true, import the functionType.
   * @throws IllegalTransformationException If the module file or the function
   *                                        cannot be located
   */
  public static void updateSignature(String moduleName, XcodeProgram xcodeml,
                                     FfunctionDefinition fctDef,
                                     FfunctionType fctType,
                                     boolean importFctType)
      throws IllegalTransformationException
  {
    FortranModule mod;
    if(Context.get().getModuleCache().isModuleLoaded(moduleName)) {
      mod = Context.get().getModuleCache().get(moduleName);
    } else {
      mod = fctDef.findContainingXmod();
      if(mod == null) {
        throw new IllegalTransformationException(
            "Unable to locate module file for: " + moduleName);
      }
      Context.get().getModuleCache().add(moduleName, mod);
    }

    FfunctionType fctTypeMod;
    if(importFctType) {
      // TODO should be part of XcodeML
      Xnode importedNode = mod.importNode(fctType);
      mod.getTypeTable().append(importedNode);
      FfunctionType importedFctType = new FfunctionType(importedNode);
      Xid importedFctTypeId = mod.createId(importedFctType.getType(),
          XstorageClass.F_FUNC, fctDef.getName());
      mod.getIdentifiers().add(importedFctTypeId);

      // check if params need to be imported as well
      if(!importedFctType.getParameters().isEmpty()) {
        for(Xnode param : importedFctType.getParameters()) {
          mod.importType(xcodeml, param.getType());
        }
      }
      return;
    } else {
      fctTypeMod = mod.findFunctionType(fctDef.getName());
    }

    if(fctTypeMod == null) {
      /* Workaround for a bug in OMNI Compiler. Look at test case
       * claw/abstraction12. In this test case, the XcodeML/F intermediate
       * representation for the function call points to a FfunctionType element
       * with no parameters. Thus, we have to matchSeq the correct FfunctionType
       * for the same function/subroutine with the same name in the module
       * symbol table. */
      String errorMsg = "Unable to locate fct " + fctDef.getName() +
          " in module " + moduleName;

      /* If not, try to matchSeq the correct FfunctionType in the module
       * definitions */
      fctTypeMod = mod.findFunctionType(fctDef.getName());
      if(fctTypeMod == null) {
        throw new IllegalTransformationException(errorMsg);
      }
    }

    FbasicType modIntTypeIntentIn =
        mod.createBasicType(FortranType.INTEGER, Intent.IN);
    mod.getTypeTable().add(modIntTypeIntentIn);

    List<Xnode> paramsLocal = fctType.getParameters();
    List<Xnode> paramsMod = fctTypeMod.getParameters();

    if(paramsLocal.size() < paramsMod.size()) {
      throw new IllegalTransformationException(
          "Local function has more parameters than module counterpart.");
    }

    for(int i = 0; i < paramsLocal.size(); ++i) {
      Xnode pLocal = paramsLocal.get(i);
      // Number of parameters in the module function as been
      if(pLocal.getBooleanAttribute(Xattr.IS_INSERTED)) {
        // new parameter
        Xnode param = mod.createAndAddParamIfNotExists(pLocal.value(),
            modIntTypeIntentIn.getType(), fctTypeMod);
        if(param != null) {
          param.setBooleanAttribute(Xattr.IS_INSERTED, true);
        }
      } else {
        Xnode pMod = paramsMod.get(i);
        String localType = pLocal.getType();
        String modType = pMod.getType();

        if(!localType.equals(modType)) {
          // Param has been updated so have to replicate the change to mod file
          FbasicType lType = xcodeml.getTypeTable().getBasicType(pLocal);
          FbasicType crtType = mod.getTypeTable().getBasicType(pMod);

          if(pLocal.hasAttribute(Xattr.PROMOTION_INFO)) {
            PromotionInfo promotionInfo = new PromotionInfo();
            promotionInfo.readDimensionsFromString(
                pLocal.getAttribute(Xattr.PROMOTION_INFO));

            if(lType.isArray()) {
              FbasicType newType = Type.duplicateWithDimension(lType, crtType,
                  xcodeml, mod, promotionInfo.getDimensions());
              pMod.setType(newType);
            }
          }
        }

        // Copy the promotion information
        pLocal.copyAttribute(pMod, Xattr.PROMOTION_INFO);
      }
    }

    // Sync attribute between local fct type and module fct type.
    for(Xattr attr : Arrays.asList(Xattr.IS_ELEMENTAL, Xattr.IS_PURE,
        Xattr.IS_FORCE_ASSUMED, Xattr.IS_RECURSIVE, Xattr.IS_PROGRAM,
        Xattr.IS_INTERNAL)) {
      fctType.syncBooleanAttribute(fctTypeMod, attr);
    }
  }
}
