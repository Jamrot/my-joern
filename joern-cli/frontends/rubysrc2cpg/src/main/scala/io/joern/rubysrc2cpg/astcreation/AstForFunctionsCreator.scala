package io.joern.rubysrc2cpg.astcreation

import io.joern.rubysrc2cpg.astcreation.RubyIntermediateAst.*
import io.joern.rubysrc2cpg.datastructures.{ConstructorScope, MethodScope}
import io.joern.rubysrc2cpg.passes.Defines
import io.joern.x2cpg.utils.NodeBuilders.{
  newBindingNode,
  newClosureBindingNode,
  newLocalNode,
  newModifierNode,
  newThisParameterNode
}
import io.joern.x2cpg.{Ast, AstEdge, ValidationMode, Defines as XDefines}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{
  DispatchTypes,
  EdgeTypes,
  EvaluationStrategies,
  ModifierTypes,
  NodeTypes,
  Operators
}
import io.joern.rubysrc2cpg.utils.FreshNameGenerator

import scala.collection.mutable

trait AstForFunctionsCreator(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>

  val procParamGen = FreshNameGenerator(i => Left(s"<proc-param-$i>"))

  /** Creates method declaration related structures.
    * @param node
    *   the node to create the AST structure from.
    * @param isClosure
    *   if true, will generate a type decl, type ref, and method ref, as well as add the `c` modifier.
    * @return
    *   a method declaration with additional refs and types if specified.
    */
  protected def astForMethodDeclaration(node: MethodDeclaration, isClosure: Boolean = false): Seq[Ast] = {
    val isInTypeDecl  = scope.surroundingAstLabel.contains(NodeTypes.TYPE_DECL)
    val isConstructor = (node.methodName == Defines.Initialize) && isInTypeDecl
    val methodName    = node.methodName
    // TODO: body could be a try
    val fullName = computeMethodFullName(methodName)
    val method = methodNode(
      node = node,
      name = methodName,
      fullName = fullName,
      code = code(node),
      signature = None,
      fileName = relativeFileName,
      astParentType = scope.surroundingAstLabel,
      astParentFullName = scope.surroundingScopeFullName
    )

    val isSurroundedByProgramScope = scope.isSurroundedByProgramScope
    if (isConstructor) scope.pushNewScope(ConstructorScope(fullName))
    else scope.pushNewScope(MethodScope(fullName, procParamGen.fresh))

    val thisParameterAst = Ast(
      newThisParameterNode(
        name = Defines.Self,
        code = Defines.Self,
        typeFullName = scope.surroundingTypeFullName.getOrElse(Defines.Any),
        line = method.lineNumber,
        column = method.columnNumber
      )
    )
    val parameterAsts = thisParameterAst :: astForParameters(node.parameters)

    val optionalStatementList = statementListForOptionalParams(node.parameters)

    val methodReturn = methodReturnNode(node, Defines.Any)

    val refs =
      List(typeRefNode(node, methodName, fullName), methodRefNode(node, methodName, fullName, fullName)).map(Ast.apply)

    // Consider which variables are captured from the outer scope
    val stmtBlockAst = if (isClosure) {
      val baseStmtBlockAst = astForMethodBody(node.body, optionalStatementList)
      transformAsClosureBody(refs, baseStmtBlockAst)
    } else {
      if (methodName == Defines.TypeDeclBody) {
        val stmtList = node.body.asInstanceOf[StatementList]
        astForStatementList(StatementList(stmtList.statements ++ optionalStatementList.statements)(stmtList.span))
      } else if (methodName != Defines.Initialize) {
        astForMethodBody(node.body, optionalStatementList)
      } else {
        astForConstructorMethodBody(node.body, optionalStatementList)
      }
    }

    // For yield statements where there isn't an explicit proc parameter
    val anonProcParam = scope.anonProcParam.map { param =>
      val paramNode = ProcParameter(param)(node.span.spanStart(s"&$param"))
      val nextIndex =
        parameterAsts.lastOption.flatMap(_.root).map { case m: NewMethodParameterIn => m.index + 1 }.getOrElse(0)
      astForParameter(paramNode, nextIndex)
    }

    scope.popScope()

    val methodTypeDeclAst = {
      val typeDeclNode_ = typeDeclNode(node, methodName, fullName, relativeFileName, code(node))
      scope.surroundingAstLabel.foreach(typeDeclNode_.astParentType(_))
      scope.surroundingScopeFullName.foreach(typeDeclNode_.astParentFullName(_))
      createMethodTypeBindings(method, typeDeclNode_)
      if isClosure then
        Ast(typeDeclNode_)
          .withChild(Ast(newModifierNode(ModifierTypes.LAMBDA)))
          .withChild(
            // This member refers back to itself, as itself is the type decl bound to the respective method
            Ast(NewMember().name("call").code("call").dynamicTypeHintFullName(Seq(fullName)).typeFullName(Defines.Any))
          )
      else Ast(typeDeclNode_)
    }

    val modifiers = mutable.Buffer(ModifierTypes.VIRTUAL)
    if (isClosure) modifiers.addOne(ModifierTypes.LAMBDA)
    if (isConstructor) modifiers.addOne(ModifierTypes.CONSTRUCTOR)

    val prefixMemberAst =
      if isClosure || isSurroundedByProgramScope then Ast() // program scope members are set elsewhere
      else {
        // Singleton constructors that initialize @@ fields should have their members linked under the singleton class
        val methodMember = scope.surroundingTypeFullName match {
          case Some(astParentTfn) => memberForMethod(method, Option(NodeTypes.TYPE_DECL), Option(astParentTfn))
          case None               => memberForMethod(method, scope.surroundingAstLabel, scope.surroundingScopeFullName)
        }
        Ast(memberForMethod(method, scope.surroundingAstLabel, scope.surroundingScopeFullName))
      }
    // For closures, we also want the method/type refs for upstream use
    val methodAst_ = {
      val mAst = methodAst(
        method,
        parameterAsts ++ anonProcParam,
        stmtBlockAst,
        methodReturn,
        modifiers.map(newModifierNode).toSeq
      )
      mAst
    }

    // Each of these ASTs are linked via AstLinker as per the astParent* properties
    (prefixMemberAst :: methodAst_ :: methodTypeDeclAst :: Nil).foreach(Ast.storeInDiffGraph(_, diffGraph))
    // In the case of a closure, we expect this method to return a method ref, otherwise, we bind a pointer to a
    // method ref, e.g. self.foo = def foo(...)
    if isClosure then refs else createMethodRefPointer(method) :: Nil
  }

  private def transformAsClosureBody(refs: List[Ast], baseStmtBlockAst: Ast) = {
    // Determine which locals are captured
    val capturedLocalNodes = baseStmtBlockAst.nodes
      .collect { case x: NewIdentifier => x }
      .distinctBy(_.name)
      .flatMap(i => scope.lookupVariable(i.name))
      .toSet
    val capturedIdentifiers = baseStmtBlockAst.nodes.collect {
      case i: NewIdentifier if capturedLocalNodes.map(_.name).contains(i.name) => i
    }
    // Copy AST block detaching the REF nodes between parent locals/params and identifiers, with the closures' one
    val capturedBlockAst = baseStmtBlockAst.copy(refEdges = baseStmtBlockAst.refEdges.filterNot {
      case AstEdge(_: NewIdentifier, dst: DeclarationNew) => capturedLocalNodes.contains(dst)
      case _                                              => false
    })

    val methodRefOption = refs.flatMap(_.nodes).collectFirst { case x: NewMethodRef => x }

    capturedLocalNodes
      .collect {
        case local: NewLocal =>
          val closureBindingId = scope.surroundingScopeFullName.map(x => s"$x:${local.name}")
          (local, local.name, local.code, closureBindingId)
        case param: NewMethodParameterIn =>
          val closureBindingId = scope.surroundingScopeFullName.map(x => s"$x:${param.name}")
          (param, param.name, param.code, closureBindingId)
      }
      .collect { case (decl, name, code, Some(closureBindingId)) =>
        val local          = newLocalNode(name, code, Option(closureBindingId))
        val closureBinding = newClosureBindingNode(closureBindingId, name, EvaluationStrategies.BY_REFERENCE)

        // Create new local node for lambda, with corresponding REF edges to identifiers and closure binding
        capturedBlockAst.withChild(Ast(local))
        capturedIdentifiers.filter(_.name == name).foreach(i => capturedBlockAst.withRefEdge(i, local))
        diffGraph.addEdge(closureBinding, decl, EdgeTypes.REF)

        methodRefOption.foreach(methodRef => diffGraph.addEdge(methodRef, closureBinding, EdgeTypes.CAPTURE))
      }

    capturedBlockAst
  }

  /** Creates the bindings between the method and its types. This is useful for resolving function pointers and imports.
    */
  protected def createMethodTypeBindings(method: NewMethod, typeDecl: NewTypeDecl): Unit = {
    val bindingNode = newBindingNode("", "", method.fullName)
    diffGraph.addEdge(typeDecl, bindingNode, EdgeTypes.BINDS)
    diffGraph.addEdge(bindingNode, method, EdgeTypes.REF)
  }

  // TODO: remaining cases
  protected def astForParameter(node: RubyNode, index: Int): Ast = {
    node match {
      case node: (MandatoryParameter | OptionalParameter) =>
        val parameterIn = parameterInNode(
          node = node,
          name = node.name,
          code = code(node),
          index = index,
          isVariadic = false,
          evaluationStrategy = EvaluationStrategies.BY_REFERENCE,
          typeFullName = None
        )
        scope.addToScope(node.name, parameterIn)
        Ast(parameterIn)
      case node: ProcParameter =>
        val parameterIn = parameterInNode(
          node = node,
          name = node.name,
          code = code(node),
          index = index,
          isVariadic = false,
          evaluationStrategy = EvaluationStrategies.BY_REFERENCE,
          typeFullName = None
        )
        scope.addToScope(node.name, parameterIn)
        scope.setProcParam(node.name)
        Ast(parameterIn)
      case node: CollectionParameter =>
        val typeFullName = node match {
          case ArrayParameter(_) => prefixAsKernelDefined("Array")
          case HashParameter(_)  => prefixAsKernelDefined("Hash")
        }
        val parameterIn = parameterInNode(
          node = node,
          name = node.name,
          code = code(node),
          index = index,
          isVariadic = true,
          evaluationStrategy = EvaluationStrategies.BY_REFERENCE,
          typeFullName = Option(typeFullName)
        )
        scope.addToScope(node.name, parameterIn)
        Ast(parameterIn)
      case node =>
        logger.warn(
          s"${node.getClass.getSimpleName} parameters are not supported yet: ${code(node)} ($relativeFileName), skipping"
        )
        astForUnknown(node)
    }
  }

  private def generateTextSpan(node: RubyNode, text: String): TextSpan = {
    TextSpan(node.span.line, node.span.column, node.span.lineEnd, node.span.columnEnd, text)
  }

  protected def statementForOptionalParam(node: OptionalParameter): RubyNode = {
    val defaultExprNode = node.defaultExpression

    IfExpression(
      UnaryExpression(
        "!",
        SimpleCall(
          SimpleIdentifier(None)(generateTextSpan(defaultExprNode, "defined?")),
          List(SimpleIdentifier(None)(generateTextSpan(defaultExprNode, node.name)))
        )(generateTextSpan(defaultExprNode, s"defined?(${node.name})"))
      )(generateTextSpan(defaultExprNode, s"!defined?(${node.name})")),
      StatementList(
        List(
          SingleAssignment(
            SimpleIdentifier(None)(generateTextSpan(defaultExprNode, node.name)),
            "=",
            node.defaultExpression
          )(generateTextSpan(defaultExprNode, s"${node.name}=${node.defaultExpression.span.text}"))
        )
      )(generateTextSpan(defaultExprNode, "")),
      List.empty,
      None
    )(
      generateTextSpan(
        defaultExprNode,
        s"if !defined?(${node.name})  \t${node.name}=${node.defaultExpression.span.text}\n  end"
      )
    )
  }

  protected def astForAnonymousTypeDeclaration(node: AnonymousTypeDeclaration): Ast = {

    // This will link the type decl to the surrounding context via base overlays
    val Seq(typeRefAst) = astForClassDeclaration(node).take(1)

    typeRefAst.nodes
      .collectFirst { case typRef: NewTypeRef =>
        val typeIdentifier = SimpleIdentifier()(node.span.spanStart(typRef.code))
        // Takes the `Class.new` before the block starts or any other keyword
        val newSpanText = typRef.code
        astForMemberCall(MemberCall(typeIdentifier, ".", "new", List.empty)(node.span.spanStart(newSpanText)))
      }
      .getOrElse(Ast())
  }

  protected def astForSingletonMethodDeclaration(node: SingletonMethodDeclaration): Seq[Ast] = {
    node.target match {
      case targetNode: SingletonMethodIdentifier =>
        val fullName = computeMethodFullName(node.methodName)

        val (astParentType, astParentFullName, thisParamCode, addEdge) = targetNode match {
          case _: SelfIdentifier =>
            (scope.surroundingAstLabel, scope.surroundingScopeFullName, Defines.Self, false)
          case _: SimpleIdentifier =>
            val baseType = node.target.span.text
            scope.surroundingTypeFullName.map(_.split("[.]").last) match {
              case Some(typ) if typ == baseType =>
                (scope.surroundingAstLabel, scope.surroundingScopeFullName, baseType, false)
              case Some(typ) =>
                scope.tryResolveTypeReference(baseType) match {
                  case Some(typ) =>
                    (Option(NodeTypes.TYPE_DECL), Option(typ.name), baseType, true)
                  case None => (None, None, Defines.Self, false)
                }
              case None => (None, None, Defines.Self, false)
            }
        }

        scope.pushNewScope(MethodScope(fullName, procParamGen.fresh))
        val method = methodNode(
          node = node,
          name = node.methodName,
          fullName = fullName,
          code = code(node),
          signature = None,
          fileName = relativeFileName
        )
        val methodTypeDecl_   = typeDeclNode(node, node.methodName, fullName, relativeFileName, code(node))
        val methodTypeDeclAst = Ast(methodTypeDecl_)
        astParentType.orElse(scope.surroundingAstLabel).foreach { t =>
          methodTypeDecl_.astParentType(t)
          method.astParentType(t)
        }
        astParentFullName.orElse(scope.surroundingScopeFullName).foreach { fn =>
          methodTypeDecl_.astParentFullName(fn)
          method.astParentFullName(fn)
        }

        createMethodTypeBindings(method, methodTypeDecl_)

        val thisParameterAst = Ast(
          newThisParameterNode(
            name = Defines.Self,
            code = thisParamCode,
            typeFullName = astParentFullName.getOrElse(Defines.Any),
            line = method.lineNumber,
            column = method.columnNumber
          )
        )

        val parameterAsts         = astForParameters(node.parameters)
        val optionalStatementList = statementListForOptionalParams(node.parameters)
        val stmtBlockAst          = astForMethodBody(node.body, optionalStatementList)

        val anonProcParam = scope.anonProcParam.map { param =>
          val paramNode = ProcParameter(param)(node.span.spanStart(s"&$param"))
          val nextIndex =
            parameterAsts.lastOption.flatMap(_.root).map { case m: NewMethodParameterIn => m.index + 1 }.getOrElse(1)
          astForParameter(paramNode, nextIndex)
        }

        scope.popScope()

        // The member for these types refers to the singleton class
        val member = memberForMethod(method, Option(NodeTypes.TYPE_DECL), astParentFullName.map(x => s"$x<class>"))
        diffGraph.addNode(member)

        val _methodAst =
          methodAst(
            method,
            (thisParameterAst +: parameterAsts) ++ anonProcParam,
            stmtBlockAst,
            methodReturnNode(node, Defines.Any),
            newModifierNode(ModifierTypes.VIRTUAL) :: Nil
          )

        _methodAst :: methodTypeDeclAst :: Nil foreach (Ast.storeInDiffGraph(_, diffGraph))
        if (addEdge) {
          Nil
        } else {
          createMethodRefPointer(method) :: Nil
        }
      case targetNode =>
        logger.warn(
          s"Target node type for singleton method declarations are not supported yet: ${targetNode.text} (${targetNode.getClass.getSimpleName}), skipping"
        )
        astForUnknown(node) :: Nil
    }
  }

  private def createMethodRefPointer(method: NewMethod): Ast = {
    if (scope.isSurroundedByProgramScope) {
      val methodRefNode = Ast(
        NewMethodRef()
          .code(s"def ${method.name} (...)")
          .methodFullName(method.fullName)
          .typeFullName(method.fullName)
          .lineNumber(method.lineNumber)
          .columnNumber(method.columnNumber)
      )

      val methodRefIdent = {
        val self = NewIdentifier().name(Defines.Self).code(Defines.Self).typeFullName(Defines.Any)
        val fi = NewFieldIdentifier()
          .code(method.name)
          .canonicalName(method.name)
          .lineNumber(method.lineNumber)
          .columnNumber(method.columnNumber)
        val fieldAccess = NewCall()
          .name(Operators.fieldAccess)
          .code(s"${Defines.Self}.${method.name}")
          .methodFullName(Operators.fieldAccess)
          .dispatchType(DispatchTypes.STATIC_DISPATCH)
          .typeFullName(Defines.Any)
        callAst(fieldAccess, Seq(Ast(self), Ast(fi)))
      }

      astForAssignment(methodRefIdent, methodRefNode, method.lineNumber, method.columnNumber)
    } else {
      Ast()
    }
  }

  private def astForParameters(parameters: List[RubyNode]): List[Ast] = {
    parameters.zipWithIndex.map { case (parameterNode, index) =>
      astForParameter(parameterNode, index + 1)
    }
  }

  private def statementListForOptionalParams(params: List[RubyNode]): StatementList = {
    StatementList(
      params
        .collect { case x: OptionalParameter =>
          x
        }
        .map(statementForOptionalParam)
    )(TextSpan(None, None, None, None, ""))
  }

  private def astForMethodBody(
    body: RubyNode,
    optionalStatementList: StatementList,
    returnLastExpression: Boolean = true
  ): Ast = {
    if (this.parseLevel == AstParseLevel.SIGNATURES) {
      Ast()
    } else {
      body match
        case stmtList: StatementList =>
          val combinedStmtList =
            StatementList(optionalStatementList.statements ++ stmtList.statements)(stmtList.span)
          if returnLastExpression then astForStatementListReturningLastExpression(combinedStmtList)
          else astForStatementList(combinedStmtList)
        case rescueExpr: RescueExpression =>
          astForRescueExpression(rescueExpr)
        case _: (StaticLiteral | BinaryExpression | SingleAssignment | SimpleIdentifier | ArrayLiteral | HashLiteral |
              SimpleCall | MemberAccess | MemberCall) =>
          val combinedStmtList =
            StatementList(optionalStatementList.statements ++ List(body))(body.span)
          if returnLastExpression then astForStatementListReturningLastExpression(combinedStmtList)
          else astForStatementList(combinedStmtList)
        case body =>
          logger.warn(
            s"Non-linear method bodies are not supported yet: ${body.text} (${body.getClass.getSimpleName}) ($relativeFileName), skipping"
          )
          astForUnknown(body)
    }
  }

  private def astForConstructorMethodBody(body: RubyNode, optionalStatementList: StatementList): Ast = {
    if (this.parseLevel == AstParseLevel.SIGNATURES) {
      Ast()
    } else {
      body match
        case stmtList: StatementList =>
          astForStatementList(StatementList(optionalStatementList.statements ++ stmtList.statements)(stmtList.span))
        case _: (StaticLiteral | BinaryExpression | SingleAssignment | SimpleIdentifier | ArrayLiteral | HashLiteral |
              SimpleCall | MemberAccess | MemberCall) =>
          astForStatementList(StatementList(optionalStatementList.statements ++ List(body))(body.span))
        case body =>
          logger.warn(
            s"Non-linear method bodies are not supported yet: ${body.text} (${body.getClass.getSimpleName}) ($relativeFileName), skipping"
          )
          astForUnknown(body)
    }
  }

}
