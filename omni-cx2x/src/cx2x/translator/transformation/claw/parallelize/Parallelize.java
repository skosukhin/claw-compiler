/*
 * This file is released under terms of BSD license
 * See LICENSE file for more information
 */

package cx2x.translator.transformation.claw.parallelize;

import cx2x.translator.common.NestedDoStatement;
import cx2x.translator.language.ClawDimension;
import cx2x.translator.language.ClawLanguage;
import cx2x.translator.language.helper.accelerator.AcceleratorHelper;
import cx2x.translator.language.helper.target.Target;
import cx2x.xcodeml.exception.IllegalTransformationException;
import cx2x.xcodeml.helper.XnodeUtil;
import cx2x.xcodeml.transformation.Transformation;
import cx2x.xcodeml.transformation.Transformer;
import cx2x.xcodeml.xnode.*;

import java.util.*;

/**
 * The parallelize transformation transforms the code contained in a
 * subroutine/function by adding necessary dimensions and parallelism to the
 * defined data.
 *
 * @author clementval
 */
public class Parallelize extends Transformation {

  private final ClawLanguage _claw;
  private final Map<String, ClawDimension> _dimensions;
  private List<String> _arrayFieldsInOut;
  private final List<String> _scalarFields;
  private int _overDimensions;
  private XfunctionDefinition _fctDef;
  private XfunctionType _fctType;
  private List<Xnode> _beforeCrt, _afterCrt;

  /**
   * Constructs a new Parallelize transformation triggered from a specific
   * pragma.
   * @param directive The directive that triggered the define transformation.
   */
  public Parallelize(ClawLanguage directive) {
    super(directive);
    _overDimensions = 0;
    _claw = directive; // Keep information about the claw directive here
    _dimensions = new HashMap<>();
    _arrayFieldsInOut = new ArrayList<>();
    _scalarFields = new ArrayList<>();
  }

  @Override
  public boolean analyze(XcodeProgram xcodeml, Transformer transformer) {

    // Check for the parent fct/subroutine definition
    _fctDef = XnodeUtil.findParentFunction(_claw.getPragma());
    if(_fctDef == null){
      xcodeml.addError("Parent function/subroutine cannot be found. " +
          "Parallelize directive must be defined in a function/subroutine.",
          _claw.getPragma().getLineNo());
      return false;
    }
    _fctType = (XfunctionType) xcodeml.getTypeTable().
        get(_fctDef.getName().getAttribute(Xattr.TYPE));
    if(_fctType == null){
      xcodeml.addError("Function/subroutine signature cannot be found. ",
          _claw.getPragma().getLineNo());
      return false;
    }

    return analyseDimension(xcodeml) && analyseData(xcodeml) &&
        analyseOver(xcodeml);
  }


  /**
   * Analyse the defined dimension.
   * @param xcodeml Current XcodeML program unit to store the error message.
   * @return True if the analysis succeeded. False otherwise.
   */
  private boolean analyseDimension(XcodeProgram xcodeml){
    if(!_claw.hasDimensionClause()){
      xcodeml.addError("No dimension defined for parallelization.",
          _claw.getPragma().getLineNo());
      return false;
    }

    for(ClawDimension d : _claw.getDimensionValues()){
      if(_dimensions.containsKey(d.getIdentifier())){
        xcodeml.addError(
            String.format("Dimension with identifier %s already specified.",
                d.getIdentifier()), _claw.getPragma().getLineNo()
        );
        return false;
      }
      _dimensions.put(d.getIdentifier(), d);
    }
    return true;
  }

  /**
   * Analyse the information defined in the data clause.
   * @param xcodeml Current XcodeML program unit to store the error message.
   * @return True if the analysis succeeded. False otherwise.
   */
  private boolean analyseData(XcodeProgram xcodeml){
    if(!_claw.hasDataClause()){
      for(XvarDecl decl : _fctDef.getDeclarationTable().getAll()){
        if(decl.isBuiltInType()){
          _scalarFields.add(decl.getName().getValue());
        }

        Xtype type =
            xcodeml.getTypeTable().get(decl.getName().getAttribute(Xattr.TYPE));
        if(type instanceof XbasicType){
          XbasicType bType = (XbasicType)type;
          if(bType.getIntent() == Xintent.INOUT && bType.isArray()){
            _arrayFieldsInOut.add(decl.getName().getValue());
          }
        }
      }
      return true;
    }
    for(String d : _claw.getDataClauseValues()){
      if(!_fctDef.getSymbolTable().contains(d)){
        xcodeml.addError(
            String.format("Data %s is not defined in the current block.", d),
            _claw.getPragma().getLineNo()
        );
        return false;
      }
      if(!_fctDef.getDeclarationTable().contains(d)){
        xcodeml.addError(
            String.format("Data %s is not declared in the current block.", d),
            _claw.getPragma().getLineNo()
        );
        return false;
      }
    }
    _arrayFieldsInOut = _claw.getDataClauseValues();
    return true;
  }

  /**
   * Analyse the information defined in the over clause.
   * @param xcodeml Current XcodeML program unit to store the error message.
   * @return True if the analysis succeeded. False otherwise.
   */
  private boolean analyseOver(XcodeProgram xcodeml){
    if(!_claw.hasOverClause()){
      _overDimensions += _claw.getDimensionValues().size();
      return true;
    }
    if(!_claw.getOverClauseValues().contains(":")){
      xcodeml.addError("The column dimension has not been specified in the " +
              "over clause. Use : to specify it.",
          _claw.getPragma().getLineNo());
      return false;
    }

    // Check if over dimensions are defined dimensions
    for(String o : _claw.getOverClauseValues()){
      if(!o.equals(ClawDimension.BASE_DIM)){
        ++_overDimensions;
        if(!_dimensions.containsKey(o)){
          xcodeml.addError(
              String.format(
                  "Dimension %s is not defined. Cannot be used in over " +
                      "clause", o), _claw.getPragma().getLineNo()
          );
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public void transform(XcodeProgram xcodeml, Transformer transformer,
                        Transformation other)
      throws Exception
  {

    // Prepare the array index that will be added to the array references.
    prepareArrayIndexes(xcodeml);

    // Insert the declarations of variables to iterate over the new dimensions.
    insertVariableToIterateOverDimension(xcodeml, transformer);

    // Promote all array fields with new dimensions.
    promoteFields(xcodeml);

    // Adapt array references.
    adaptArrayReferences(_arrayFieldsInOut);

    // Delete the pragma
    _claw.getPragma().delete();

    // Apply specific target transformation
    if(_claw.getTarget() == Target.GPU){
      transformForGPU(xcodeml);
    } else {
      transformForCPU(xcodeml);
    }

    XmoduleDefinition modDef = XnodeUtil.findParentModule(_fctDef);
    if(modDef != null){
      updateModuleSignature(xcodeml, _fctDef, modDef, transformer);
    }
  }

  /**
   * Apply GPU based transformation.
   * @param xcodeml     Current XcodeML program unit.
   */
  private void transformForGPU(XcodeProgram xcodeml)
  {
    /* Create a nested loop with the new defined dimensions and wrap it around
     * the whole subroutine's body. This is for the moment a really naive
     * transformation idea but it is our start point. */
    NestedDoStatement loops =
        new NestedDoStatement(getOrderedDimensionsFromDefinition(), xcodeml);
    XnodeUtil.copyBody(_fctDef.getBody(), loops.getInnerStatement());
    _fctDef.getBody().delete();
    Xnode newBody = new Xnode(Xcode.BODY, xcodeml);
    newBody.appendToChildren(loops.getOuterStatement(), false);
    _fctDef.appendToChildren(newBody, false);

    AcceleratorHelper.generateParallelLoopClause(_claw, xcodeml,
        loops.getOuterStatement(), loops.getOuterStatement(),
        loops.getGroupSize());
  }

  /**
   * Apply CPU based transformations.
   * @param xcodeml     Current XcodeML program unit
   * @throws Exception
   */
  private void transformForCPU(XcodeProgram xcodeml)
      throws Exception
  {
    /* Create a group of nested loop with the newly defined dimension and wrap
     * every assignment statement in the column loop or including data with it.
     * This is for the moment a really naive transformation idea but it is our
     * start point. */


    List<ClawDimension> order = getOrderedDimensionsFromDefinition();
    List<Xnode> assignStatements =
        XnodeUtil.findAll(Xcode.FASSIGNSTATEMENT, _fctDef.getBody());

    for(Xnode assign : assignStatements){
      if(assign.getChild(0).Opcode() == Xcode.FARRAYREF){
        Xnode ref = assign.getChild(0);

        if(_arrayFieldsInOut.contains(ref.find(Xcode.VARREF, Xcode.VAR).getValue())){
          NestedDoStatement loops = new NestedDoStatement(order, xcodeml);
          XnodeUtil.insertAfter(assign, loops.getOuterStatement());
          loops.getInnerStatement().getBody().appendToChildren(assign, true);
          assign.delete();
        }
      } else if(assign.getChild(0).Opcode() == Xcode.VAR
          && _scalarFields.contains(assign.getChild(0).getValue()))
      {
        /* If the assignment is in the column loop and is composed with some
         * variables, the field must be promoted and the var reference switch
         * to an array reference */
        Xnode lhs = assign.getChild(0);
        List<Xnode> vars = XnodeUtil.findAllReferences(assign);
        if(vars.size() > 1){
          if(!_arrayFieldsInOut.contains(lhs.getValue())){
            _arrayFieldsInOut.add(lhs.getValue());
            promoteField(lhs.getValue(), false, false, xcodeml);
          }
          adaptScalarRefToArrayReferences(xcodeml,
              Collections.singletonList(lhs.getValue()));
          NestedDoStatement loops = new NestedDoStatement(order, xcodeml);
          XnodeUtil.insertAfter(assign, loops.getOuterStatement());
          loops.getInnerStatement().getBody().appendToChildren(assign, true);
          assign.delete();
        }
      }
    }
  }

  /**
   * Prepare the arrayIndex elements that will be inserted before and after the
   * current indexes in the array references.
   * @param xcodeml Current XcodeML program unit in which new elements are
   *                created.
   */
  private void prepareArrayIndexes(XcodeProgram xcodeml) {
    _beforeCrt = new ArrayList<>();
    _afterCrt = new ArrayList<>();
    List<Xnode> crt = _beforeCrt;

    if(_claw.hasOverClause()) {
      /* If the over clause is specified, the indexes respect the definition of
       * the over clause. Indexes before the "colon" symbol will be inserted
       * before the current indexes and the remaining indexes will be inserted
       * after the current indexes.  */

      for (String dim : _claw.getOverClauseValues()) {
        if (dim.equals(ClawDimension.BASE_DIM)) {
          crt = _afterCrt;
        } else {
          ClawDimension d = _dimensions.get(dim);
          crt.add(d.generateArrayIndex(xcodeml));
        }
      }

    } else {
      /* If no over clause, the indexes are inserted from the defined dimensions
       * from left to right. Everything is inserted on the left of current
       * indexes */

      for(ClawDimension dim : _claw.getDimensionValues()){
        crt.add(dim.generateArrayIndex(xcodeml));
      }
    }

    Collections.reverse(_beforeCrt); // Because of insertion order
  }


  /**
   * Promote all fields declared in the data clause with the additional
   * dimensions.
   * @param xcodeml Current XcodeML program unit in which the element will be
   *                created.
   * @throws IllegalTransformationException if elements cannot be created or
   * elements cannot be found.
   */
  private void promoteFields(XcodeProgram xcodeml)
      throws IllegalTransformationException
  {
    // Promote arrays
    for(String fieldId : _arrayFieldsInOut){
      promoteField(fieldId, true, true, xcodeml);
    }
  }

  /**
   * Promote a field with the information stored in the defined dimensions.
   * @param fieldId Id of the field as defined in the symbol table.
   * @param update  If true, update current type otherwise, create a type from
   *                scratch.
   * @param assumed If true, generate assumed dimension range, otherwise, use
   *                the information in the defined dimension.
   * @param xcodeml Current XcodeML program unit in which the element will be
   *                created.
   * @throws IllegalTransformationException
   */
  private void promoteField(String fieldId, boolean update, boolean assumed,
                            XcodeProgram xcodeml)
      throws IllegalTransformationException
  {
    Xid id = _fctDef.getSymbolTable().get(fieldId);
    XvarDecl decl = _fctDef.getDeclarationTable().get(fieldId);
    String type = xcodeml.getTypeTable().generateArrayTypeHash();
    XbasicType newType;

    if(update){
      XbasicType oldType = (XbasicType) xcodeml.getTypeTable().get(id.getType());
      if(oldType == null){
        throw new IllegalTransformationException("Cannot find type for " +
            fieldId, _claw.getPragma().getLineNo());
      }
      newType = oldType.cloneObject();
      newType.setType(type);
    } else {
      newType = XnodeUtil.createBasicType(xcodeml, type, id.getType(),
          Xintent.NONE);
    }
    if(assumed){
      for(int i = 0; i < _overDimensions; ++i){
        Xnode index = XnodeUtil.createEmptyAssumedShaped(xcodeml);
        newType.addDimension(index, 0);
      }
    } else {
      for(ClawDimension dim : _claw.getDimensionValues()){
        Xnode index = dim.generateIndexRange(xcodeml, false);
        newType.addDimension(index, 0);
      }
    }
    id.setType(type);
    decl.getName().setAttribute(Xattr.TYPE, type);
    xcodeml.getTypeTable().add(newType);

    // Update params in function type
    for(Xnode param : _fctType.getParams().getAll()){
      if(param.getValue().equals(fieldId)){
        param.setAttribute(Xattr.TYPE, type);
      }
    }
  }

  /**
   * Adapt all the array references of the variable in the data clause in the
   * current function/subroutine definition.
   * @param ids List of array identifiers that must be adapted.
   */
  private void adaptArrayReferences(List<String> ids) {
    for(String data : ids){
      List<Xnode> refs =
          XnodeUtil.getAllArrayReferences(_fctDef.getBody(), data);
      for(Xnode ref : refs){
        for(Xnode ai : _beforeCrt){
          XnodeUtil.insertAfter(ref.find(Xcode.VARREF), ai.cloneObject());
        }
        for(Xnode ai : _afterCrt){
          List<Xnode> children = ref.getChildren();
          XnodeUtil.insertAfter(children.get(children.size()-1),
              ai.cloneObject());
        }
      }
    }
  }

  /**
   * Adapt all the array references of the variable in the data clause in the
   * current function/subroutine definition.
   * @param xcodeml Current XcodeML program unit in which the element will be
   *                created.
   */
  private void adaptScalarRefToArrayReferences(XcodeProgram xcodeml,
                                               List<String> ids)
  {
    for(String id : ids){
      List<Xnode> vars = XnodeUtil.findAllReferences(_fctDef.getBody(), id);

      Xid sId = _fctDef.getSymbolTable().get(id);
      XbasicType type = (XbasicType) xcodeml.getTypeTable().get(sId.getType());

      for(Xnode var : vars){
        Xnode ref =
            XnodeUtil.createArrayRef(xcodeml, type, var.cloneObject());
        for(Xnode ai : _beforeCrt){
          XnodeUtil.insertAfter(ref.find(Xcode.VARREF), ai.cloneObject());
        }
        for(Xnode ai : _afterCrt){
          ref.appendToChildren(ai, true);
        }

        XnodeUtil.insertAfter(var, ref);
        var.delete();
      }
    }
  }

  /**
   * Insert the declaration of the different variables needed to iterate over
   * the additional dimensions.
   * @param xcodeml     Current XcodeML program unit in which element are
   *                    created.
   * @param transformer Current transformer object.
   */
  private void insertVariableToIterateOverDimension(XcodeProgram xcodeml,
                                                    Transformer transformer)
      throws IllegalTransformationException
  {
    // Create type and declaration for iterations over the new dimensions
    XbasicType intTypeIntentIn = XnodeUtil.createBasicType(xcodeml,
        xcodeml.getTypeTable().generateIntegerTypeHash(),
        Xname.TYPE_F_INT, Xintent.IN);
    xcodeml.getTypeTable().add(intTypeIntentIn);

    // For each dimension defined in the directive
    for(ClawDimension dimension : _claw.getDimensionValues()){
      // Create the parameter for the lower bound
      if(dimension.lowerBoundIsVar()){
        XnodeUtil.createIdAndDecl(dimension.getLowerBoundId(),
            intTypeIntentIn.getType(), Xname.SCLASS_F_PARAM, _fctDef, xcodeml);

        // Add parameter to the local type table
        XnodeUtil.createAndAddParam(xcodeml, dimension.getLowerBoundId(),
            intTypeIntentIn.getType(), _fctType);
      }

      // Create parameter for the upper bound
      if(dimension.upperBoundIsVar()){
        XnodeUtil.createIdAndDecl(dimension.getUpperBoundId(),
            intTypeIntentIn.getType(), Xname.SCLASS_F_PARAM, _fctDef, xcodeml);

        // Add parameter to the local type table
        XnodeUtil.createAndAddParam(xcodeml, dimension.getUpperBoundId(),
            intTypeIntentIn.getType(), _fctType);
      }
      // Create induction variable declaration
      XnodeUtil.createIdAndDecl(dimension.getIdentifier(), Xname.TYPE_F_INT,
          Xname.SCLASS_F_LOCAL, _fctDef, xcodeml);
    }
  }

  /**
   * Update the function signature in the module file to reflects local changes.
   * @param xcodeml     Current XcodeML file unit.
   * @param fctDef      Function definition that has been changed.
   * @param modDef      Module definition holding the function definition.
   * @param transformer Current transformer object.
   * @throws IllegalTransformationException If the module file or the function
   * cannot be located
   */
  private void updateModuleSignature(XcodeProgram xcodeml,
                                     XfunctionDefinition fctDef,
                                     XmoduleDefinition modDef,
                                     Transformer transformer)
    throws IllegalTransformationException
  {
    Xmod mod;
    if(transformer.getModCache().isModuleLoaded(modDef.getName())){
      mod = transformer.getModCache().get(modDef.getName());
    } else {
      mod = XnodeUtil.findContainingModule(fctDef);
      transformer.getModCache().add(modDef.getName(), mod);
      if(mod == null){
        throw new IllegalTransformationException(
            "Unable to locate module file for: " + modDef.getName(),
            _claw.getPragma().getLineNo());
      }
    }

    XfunctionType fctTypeMod = (XfunctionType) mod.getTypeTable().get(
        fctDef.getName().getAttribute(Xattr.TYPE));
    if(fctTypeMod == null){
      throw new IllegalTransformationException(
          "Unable to locate fct " + fctDef.getName() + " in module " +
              modDef.getName(), _claw.getPragma().getLineNo());
    }
    XbasicType modIntTypeIntentIn = XnodeUtil.createBasicType(mod,
          mod.getTypeTable().generateIntegerTypeHash(),
          Xname.TYPE_F_INT, Xintent.IN);
    mod.getTypeTable().add(modIntTypeIntentIn);

    List<Xnode> paramsLocal = _fctType.getParams().getAll();
    List<Xnode> paramsMod = fctTypeMod.getParams().getAll();


    if(paramsLocal.size() < paramsMod.size()){
      throw new IllegalTransformationException(
          "Local function has more parameters than module counterpart.",
          _claw.getPragma().getLineNo());
    }

    for(int i = 0; i < paramsLocal.size(); i++){
      Xnode pLocal = paramsLocal.get(i);
      if(i > (paramsMod.size() - 1)) {
        // new parameter
        XnodeUtil.createAndAddParam(mod, pLocal.getValue(),
            modIntTypeIntentIn.getType(), fctTypeMod);
      } else {
        Xnode pMod = paramsMod.get(i);
        String localType = pLocal.getAttribute(Xattr.TYPE);
        String modType = pMod.getAttribute(Xattr.TYPE);
        if(!localType.equals(modType)){
          // Param has been update so have to replicate the change to mod file
          XbasicType lType = (XbasicType)xcodeml.getTypeTable().get(localType);
          XbasicType crtType = (XbasicType)mod.getTypeTable().get(modType);

          if(lType.isArray()) {
            String newType = mod.getTypeTable().generateArrayTypeHash();
            XbasicType newBasicType = XnodeUtil.createBasicType(mod, newType,
                crtType.getType(), crtType.getIntent());
            for(int j = 0; j < lType.getDimensions(); ++j){
              Xnode assumed = XnodeUtil.createEmptyAssumedShaped(mod);
              newBasicType.appendToChildren(assumed, false);
            }
            mod.getTypeTable().add(newBasicType);
            pMod.setAttribute(Xattr.TYPE, newType);
          }
        }
      }
    }

  }

  /**
   * Get the list of dimensions in order from the parallelize over definition.
   * @return Ordered list of dimension object.
   */
  private List<ClawDimension> getOrderedDimensionsFromDefinition(){
    if(_claw.hasOverClause()){
      List<ClawDimension> dimensions = new ArrayList<>();
      for(String o : _claw.getOverClauseValues()) {
        if (o.equals(ClawDimension.BASE_DIM)) {
          continue;
        }
        dimensions.add(_dimensions.get(o));
      }
      return dimensions;
    } else {
      return _claw.getDimensionValuesReversed();
    }
  }


  @Override
  public boolean canBeTransformedWith(Transformation other) {
    return false; // This is an independent transformation
  }
}