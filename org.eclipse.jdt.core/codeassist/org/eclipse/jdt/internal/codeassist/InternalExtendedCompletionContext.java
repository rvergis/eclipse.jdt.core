/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.codeassist;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.codeassist.complete.CompletionParser;
import org.eclipse.jdt.internal.codeassist.impl.AssistCompilationUnit;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Initializer;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.ImportBinding;
import org.eclipse.jdt.internal.compiler.lookup.InvocationSite;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.SignatureWrapper;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilation;
import org.eclipse.jdt.internal.compiler.util.ObjectVector;
import org.eclipse.jdt.internal.core.CompilationUnitElementInfo;
import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.LocalVariable;
import org.eclipse.jdt.internal.core.util.Util;

public class InternalExtendedCompletionContext {
	private static Util.BindingsToNodesMap EmptyNodeMap = new Util.BindingsToNodesMap() {
		public ASTNode get(Binding binding) {
			return null;
		}
	};
	
	private InternalCompletionContext completionContext;
	
	// static data
	private ITypeRoot typeRoot;
	private CompilationUnitDeclaration compilationUnitDeclaration;
	private LookupEnvironment lookupEnvironment;
	private Scope assistScope;
	private ASTNode assistNode;
	private WorkingCopyOwner owner;
	
	private CompletionParser parser;
	
	// computed data
	private boolean hasComputedVisibleElementBindings;
	private ObjectVector visibleLocalVariables;
	private ObjectVector visibleFields;
	private ObjectVector visibleMethods;
	
	private boolean hasComputedEnclosingJavaElements;
	Map bindingsToNodes;
	private Map bindingsToHandles;
	private ICompilationUnit compilationUnit;
	
	public InternalExtendedCompletionContext(
			InternalCompletionContext completionContext,
			ITypeRoot typeRoot,
			CompilationUnitDeclaration compilationUnitDeclaration,
			LookupEnvironment lookupEnvironment,
			Scope assistScope,
			ASTNode assistNode,
			WorkingCopyOwner owner,
			CompletionParser parser) {
		this.completionContext = completionContext;
		this.typeRoot = typeRoot;
		this.compilationUnitDeclaration = compilationUnitDeclaration;
		this.lookupEnvironment = lookupEnvironment;
		this.assistScope = assistScope;
		this.assistNode = assistNode;
		this.owner = owner;
		this.parser = parser;
	}
	
	private void computeEnclosingJavaElements() {
		this.hasComputedEnclosingJavaElements = true;
		
		if (this.typeRoot == null) return;
		
		if (this.typeRoot.getElementType() == IJavaElement.COMPILATION_UNIT) {
	 		ICompilationUnit original = (org.eclipse.jdt.core.ICompilationUnit)this.typeRoot;
			
			HashMap handleToBinding = new HashMap();
			HashMap bindingToHandle = new HashMap();
			HashMap handleToInfo = new HashMap();
			
			org.eclipse.jdt.core.ICompilationUnit handle = new AssistCompilationUnit(original, this.owner, handleToBinding, handleToInfo);
			CompilationUnitElementInfo info = new CompilationUnitElementInfo();
			
			handleToInfo.put(handle, info);
			
			CompletionUnitStructureRequestor structureRequestor = 
				new CompletionUnitStructureRequestor(
						handle,
						info,
						this.parser,
						this.assistNode,
						handleToBinding,
						bindingToHandle,
						handleToInfo);
			
			CompletionElementNotifier notifier =
				new CompletionElementNotifier(
						structureRequestor,
						true,
						this.assistNode);
			
			notifier.notifySourceElementRequestor(
					this.compilationUnitDeclaration,
					this.compilationUnitDeclaration.sourceStart,
					this.compilationUnitDeclaration.sourceEnd,
					false,
					this.parser.sourceEnds,
					new HashMap());
			
			this.bindingsToHandles = bindingToHandle;
			this.compilationUnit = handle;
		}
	}
	
	private void computeVisibleElementBindings() {
		this.hasComputedVisibleElementBindings = true;
		
		Scope scope = this.assistScope;
		ASTNode astNode = this.assistNode;
		boolean notInJavadoc = this.completionContext.javadoc == 0;
		
		this.visibleLocalVariables = new ObjectVector();
		this.visibleFields = new ObjectVector();
		this.visibleMethods = new ObjectVector();
		this.bindingsToNodes = new HashMap();
		
		ReferenceContext referenceContext = scope.referenceContext();
		if (referenceContext instanceof AbstractMethodDeclaration) {
			// completion is inside a method body
			searchVisibleVariablesAndMethods(scope, visibleLocalVariables, visibleFields, visibleMethods, notInJavadoc);
		} else if (referenceContext instanceof TypeDeclaration) {
			TypeDeclaration typeDeclaration = (TypeDeclaration) referenceContext;
			FieldDeclaration[] fields = typeDeclaration.fields;
			if (fields != null) {
				done : for (int i = 0; i < fields.length; i++) {
					if (fields[i] instanceof Initializer) {
						Initializer initializer = (Initializer) fields[i];
						if (initializer.block.sourceStart <= astNode.sourceStart &&
								astNode.sourceStart < initializer.bodyEnd) {
							// completion is inside an initializer
							searchVisibleVariablesAndMethods(scope, visibleLocalVariables, visibleFields, visibleMethods, notInJavadoc);
							break done;
						}
					} else {
						FieldDeclaration fieldDeclaration = fields[i];
						if (fieldDeclaration.initialization != null && 
								fieldDeclaration.initialization.sourceStart <= astNode.sourceStart &&
								astNode.sourceEnd <= fieldDeclaration.initialization.sourceEnd) {
							// completion is inside a field initializer
							searchVisibleVariablesAndMethods(scope, visibleLocalVariables, visibleFields, visibleMethods, notInJavadoc);
							break done;
						}  
					}
				}
			}
		}
	}
	
	public IJavaElement getEnclosingElement() {
		try {
			if (!this.hasComputedEnclosingJavaElements) {
				this.computeEnclosingJavaElements();
			}
			if (this.compilationUnit == null) return null;
			IJavaElement enclosingElement = compilationUnit.getElementAt(this.completionContext.offset);
			return enclosingElement == null ? this.compilationUnit : enclosingElement;
		} catch (JavaModelException e) {
			Util.log(e, "Cannot compute enclosing element"); //$NON-NLS-1$
			return null;
		}
	}
	
	private JavaElement getJavaElement(LocalVariableBinding binding) {
		LocalDeclaration local = binding.declaration;
		
		JavaElement parent = null;
		ReferenceContext referenceContext = binding.declaringScope.referenceContext();
		if (referenceContext instanceof AbstractMethodDeclaration) {
			AbstractMethodDeclaration methodDeclaration = (AbstractMethodDeclaration) referenceContext;
			parent = this.getJavaElementOfCompilationUnit(methodDeclaration.binding);
		} else if (referenceContext instanceof TypeDeclaration){
			// Local variable is declared inside an initializer
			TypeDeclaration typeDeclaration = (TypeDeclaration) referenceContext;
			
			IType type = (IType)this.getJavaElementOfCompilationUnit(typeDeclaration.binding);
			if (type != null) {
				try {
					IInitializer[] initializers = type.getInitializers();
					if (initializers != null) {
						done : for (int i = 0; i < initializers.length; i++) {
							IInitializer initializer = initializers[i];
							ISourceRange sourceRange = initializer.getSourceRange();
							if (sourceRange != null) {
								int initializerStart = sourceRange.getOffset();
								int initializerEnd = initializerStart + sourceRange.getLength();
								if (initializerStart <= local.sourceStart &&
										local.sourceEnd <= initializerEnd) {
									parent = (JavaElement)initializer;
									break done;
								}
							}
						}
					}
				} catch (JavaModelException e) {
					return null;
				}
			}
		}
		if (parent == null) return null;
		
		return new LocalVariable(
				parent,
				new String(local.name),
				local.declarationSourceStart,
				local.declarationSourceEnd,
				local.sourceStart,
				local.sourceEnd,
				Util.typeSignature(local.type),
				binding.declaration.annotations);
	}
	
	private JavaElement getJavaElementOfCompilationUnit(Binding binding) {
		if (!this.hasComputedEnclosingJavaElements) {
			computeEnclosingJavaElements();
		}
		if (this.bindingsToHandles == null) return null;
		return (JavaElement)this.bindingsToHandles.get(binding);
	}
	
	private TypeBinding getTypeFromSignature(String typeSignature, Scope scope) {
		TypeBinding assignableTypeBinding = null;
		
		TypeVariableBinding[] typeVariables = Binding.NO_TYPE_VARIABLES;
		ReferenceContext referenceContext = scope.referenceContext();
		if (referenceContext instanceof AbstractMethodDeclaration) {
			AbstractMethodDeclaration methodDeclaration = (AbstractMethodDeclaration) referenceContext;
			typeVariables = methodDeclaration.binding.typeVariables;
		}
		
		CompilationUnitDeclaration previousUnitBeingCompleted = lookupEnvironment.unitBeingCompleted;
		lookupEnvironment.unitBeingCompleted = this.compilationUnitDeclaration;
		try {
			
			SignatureWrapper wrapper = new SignatureWrapper(typeSignature.toCharArray());
			assignableTypeBinding = lookupEnvironment.getTypeFromTypeSignature(wrapper, typeVariables, this.assistScope.enclosingClassScope().referenceContext.binding, null);
			if (assignableTypeBinding instanceof ReferenceBinding) {
				assignableTypeBinding = BinaryTypeBinding.resolveType((ReferenceBinding)assignableTypeBinding, lookupEnvironment, true);
			}
		} catch (AbortCompilation e) {
			assignableTypeBinding = null;
		} finally {
			lookupEnvironment.unitBeingCompleted = previousUnitBeingCompleted;
		}
		return assignableTypeBinding;
	}
	
	public IJavaElement[] getVisibleElements(String typeSignature) {
		if (this.assistScope == null) return new IJavaElement[0];
		
		if (!this.hasComputedVisibleElementBindings) {
			this.computeVisibleElementBindings();
		}
		
		TypeBinding assignableTypeBinding = null;
		if (typeSignature != null) {
			assignableTypeBinding = this.getTypeFromSignature(typeSignature, this.assistScope);
			if (assignableTypeBinding == null) return new IJavaElement[0];
		}
		 
		int length = visibleLocalVariables.size() + visibleFields.size() + visibleMethods.size();
		if (length == 0) return new IJavaElement[0];
		
		IJavaElement[] result = new IJavaElement[length];
		
		int elementCount = 0;
		
		int size = visibleLocalVariables.size();
		if (size > 0) {
			next : for (int i = 0; i < size; i++) {
				LocalVariableBinding binding = (LocalVariableBinding) visibleLocalVariables.elementAt(i);
				if (assignableTypeBinding != null && !binding.type.isCompatibleWith(assignableTypeBinding)) continue next;
				result[elementCount++] = getJavaElement(binding);
			}
		
		}
		size = visibleFields.size();
		if (size > 0) {
			next : for (int i = 0; i < size; i++) {
				FieldBinding binding = (FieldBinding) visibleFields.elementAt(i);
				if (assignableTypeBinding != null && !binding.type.isCompatibleWith(assignableTypeBinding)) continue next;
				if (this.assistScope.isDefinedInSameUnit(binding.declaringClass)) {
					JavaElement field = getJavaElementOfCompilationUnit(binding);
					if (field != null) result[elementCount++] = field;
				} else {
					JavaElement field = Util.getUnresolvedJavaElement(binding, owner, EmptyNodeMap);
					if (field != null) result[elementCount++] = field.resolved(binding);
				}
			}
		
		}
		size = visibleMethods.size();
		if (size > 0) {
			next : for (int i = 0; i < size; i++) {
				MethodBinding binding = (MethodBinding) visibleMethods.elementAt(i);
				if (assignableTypeBinding != null && !binding.returnType.isCompatibleWith(assignableTypeBinding)) continue next;
				if (this.assistScope.isDefinedInSameUnit(binding.declaringClass)) {
					JavaElement method = getJavaElementOfCompilationUnit(binding);
					if (method != null) result[elementCount++] = method;
				} else {
					JavaElement method = Util.getUnresolvedJavaElement(binding, owner, EmptyNodeMap);
					if (method != null) result[elementCount++] = method.resolved(binding);
				}
				
			}
		}
		
		if (elementCount != result.length) {
			System.arraycopy(result, 0, result = new IJavaElement[elementCount], 0, elementCount);
		}

		return result;
	}
	
	private void searchVisibleFields(
			FieldBinding[] fields,
			ReferenceBinding receiverType,
			Scope scope,
			InvocationSite invocationSite,
			Scope invocationScope,
			boolean onlyStaticFields,
			ObjectVector localsFound,
			ObjectVector fieldsFound) {
		ObjectVector newFieldsFound = new ObjectVector();
		// Inherited fields which are hidden by subclasses are filtered out
		// No visibility checks can be performed without the scope & invocationSite
		
		next : for (int f = fields.length; --f >= 0;) {			
			FieldBinding field = fields[f];

			if (field.isSynthetic()) continue next;
			
			if (onlyStaticFields && !field.isStatic()) continue next;
			
			if (!field.canBeSeenBy(receiverType, invocationSite, scope)) continue next;
			
			for (int i = fieldsFound.size; --i >= 0;) {
				FieldBinding otherField = (FieldBinding) fieldsFound.elementAt(i);
				if (CharOperation.equals(field.name, otherField.name, true)) {
					continue next;
				}
			}

			for (int l = localsFound.size; --l >= 0;) {
				LocalVariableBinding local = (LocalVariableBinding) localsFound.elementAt(l);	

				if (CharOperation.equals(field.name, local.name, true)) {
					continue next;
				}
			}
			
			newFieldsFound.add(field);
		}
		
		fieldsFound.addAll(newFieldsFound);
	}
	
	private void searchVisibleFields(
			ReferenceBinding receiverType,
			Scope scope,
			InvocationSite invocationSite,
			Scope invocationScope,
			boolean onlyStaticFields,
			boolean notInJavadoc,
			ObjectVector localsFound,
			ObjectVector fieldsFound) {

		ReferenceBinding currentType = receiverType;
		ReferenceBinding[] interfacesToVisit = null;
		int nextPosition = 0;
		do {
			ReferenceBinding[] itsInterfaces = currentType.superInterfaces();
			if (notInJavadoc && itsInterfaces != Binding.NO_SUPERINTERFACES) {
				if (interfacesToVisit == null) {
					interfacesToVisit = itsInterfaces;
					nextPosition = interfacesToVisit.length;
				} else {
					int itsLength = itsInterfaces.length;
					if (nextPosition + itsLength >= interfacesToVisit.length)
						System.arraycopy(interfacesToVisit, 0, interfacesToVisit = new ReferenceBinding[nextPosition + itsLength + 5], 0, nextPosition);
					nextInterface : for (int a = 0; a < itsLength; a++) {
						ReferenceBinding next = itsInterfaces[a];
						for (int b = 0; b < nextPosition; b++)
							if (next == interfacesToVisit[b]) continue nextInterface;
						interfacesToVisit[nextPosition++] = next;
					}
				}
			}

			FieldBinding[] fields = currentType.availableFields();
			if(fields != null && fields.length > 0) {
				
				searchVisibleFields(
						fields,
						receiverType,
						scope,
						invocationSite,
						invocationScope,
						onlyStaticFields,
						localsFound,
						fieldsFound);
			}
			currentType = currentType.superclass();
		} while (notInJavadoc && currentType != null);

		if (notInJavadoc && interfacesToVisit != null) {
			for (int i = 0; i < nextPosition; i++) {
				ReferenceBinding anInterface = interfacesToVisit[i];
				FieldBinding[] fields = anInterface.availableFields();
				if(fields !=  null) {
					searchVisibleFields(
							fields,
							receiverType,
							scope,
							invocationSite,
							invocationScope,
							onlyStaticFields,
							localsFound,
							fieldsFound);
				}

				ReferenceBinding[] itsInterfaces = anInterface.superInterfaces();
				if (itsInterfaces != Binding.NO_SUPERINTERFACES) {
					int itsLength = itsInterfaces.length;
					if (nextPosition + itsLength >= interfacesToVisit.length)
						System.arraycopy(interfacesToVisit, 0, interfacesToVisit = new ReferenceBinding[nextPosition + itsLength + 5], 0, nextPosition);
					nextInterface : for (int a = 0; a < itsLength; a++) {
						ReferenceBinding next = itsInterfaces[a];
						for (int b = 0; b < nextPosition; b++)
							if (next == interfacesToVisit[b]) continue nextInterface;
						interfacesToVisit[nextPosition++] = next;
					}
				}
			}
		}
	}

	private void searchVisibleInterfaceMethods(
			ReferenceBinding[] itsInterfaces,
			ReferenceBinding receiverType,
			Scope scope,
			InvocationSite invocationSite,
			Scope invocationScope,
			boolean onlyStaticMethods,
			ObjectVector methodsFound) {
		if (itsInterfaces != Binding.NO_SUPERINTERFACES) {
			ReferenceBinding[] interfacesToVisit = itsInterfaces;
			int nextPosition = interfacesToVisit.length;

			for (int i = 0; i < nextPosition; i++) {
				ReferenceBinding currentType = interfacesToVisit[i];
				MethodBinding[] methods = currentType.availableMethods();
				if(methods != null) {
					searchVisibleLocalMethods(
							methods,
							receiverType,
							scope,
							invocationSite,
							invocationScope,
							onlyStaticMethods,
							methodsFound);
				}

				itsInterfaces = currentType.superInterfaces();
				if (itsInterfaces != null && itsInterfaces != Binding.NO_SUPERINTERFACES) {
					int itsLength = itsInterfaces.length;
					if (nextPosition + itsLength >= interfacesToVisit.length)
						System.arraycopy(interfacesToVisit, 0, interfacesToVisit = new ReferenceBinding[nextPosition + itsLength + 5], 0, nextPosition);
					nextInterface : for (int a = 0; a < itsLength; a++) {
						ReferenceBinding next = itsInterfaces[a];
						for (int b = 0; b < nextPosition; b++)
							if (next == interfacesToVisit[b]) continue nextInterface;
						interfacesToVisit[nextPosition++] = next;
					}
				}
			}
		}
	}
	
	private void searchVisibleLocalMethods(
			MethodBinding[] methods,
			ReferenceBinding receiverType,
			Scope scope,
			InvocationSite invocationSite,
			Scope invocationScope,
			boolean onlyStaticMethods,
			ObjectVector methodsFound) {
		ObjectVector newMethodsFound =  new ObjectVector();
		// Inherited methods which are hidden by subclasses are filtered out
		// No visibility checks can be performed without the scope & invocationSite

		next : for (int f = methods.length; --f >= 0;) {
			MethodBinding method = methods[f];

			if (method.isSynthetic()) continue next;

			if (method.isDefaultAbstract())	continue next;

			if (method.isConstructor()) continue next;
			
			if (onlyStaticMethods && !method.isStatic()) continue next;

			if (!method.canBeSeenBy(receiverType, invocationSite, scope)) continue next;
			
			for (int i = methodsFound.size; --i >= 0;) {
				MethodBinding otherMethod = (MethodBinding) methodsFound.elementAt(i);
				if (method == otherMethod)
					continue next;
				
				if (CharOperation.equals(method.selector, otherMethod.selector, true)) {
					if (this.lookupEnvironment.methodVerifier().isMethodSubsignature(otherMethod, method)) {
						continue next;
					}
				}
			}

			newMethodsFound.add(method);
		}
		
		methodsFound.addAll(newMethodsFound);
	}
	
	private void searchVisibleMethods(
			ReferenceBinding receiverType,
			Scope scope,
			InvocationSite invocationSite,
			Scope invocationScope,
			boolean onlyStaticMethods,
			boolean notInJavadoc,
			ObjectVector methodsFound) {
		ReferenceBinding currentType = receiverType;
		if (notInJavadoc) {
			if (receiverType.isInterface()) {
				searchVisibleInterfaceMethods(
						new ReferenceBinding[]{currentType},
						receiverType,
						scope,
						invocationSite,
						invocationScope,
						onlyStaticMethods,
						methodsFound);
				
				currentType = scope.getJavaLangObject();
			}
		}
		boolean hasPotentialDefaultAbstractMethods = true;
		while (currentType != null) {
			
			MethodBinding[] methods = currentType.availableMethods();
			if (methods != null) {
				searchVisibleLocalMethods(
						methods,
						receiverType,
						scope,
						invocationSite,
						invocationScope,
						onlyStaticMethods,
						methodsFound);
			}
			
			if (notInJavadoc &&
					hasPotentialDefaultAbstractMethods &&
					(currentType.isAbstract() ||
							currentType.isTypeVariable() ||
							currentType.isIntersectionType() ||
							currentType.isEnum())){
				
				ReferenceBinding[] superInterfaces = currentType.superInterfaces();
				if (superInterfaces != null && currentType.isIntersectionType()) {
					for (int i = 0; i < superInterfaces.length; i++) {
						superInterfaces[i] = (ReferenceBinding)superInterfaces[i].capture(invocationScope, invocationSite.sourceEnd());
					}
				}
				
				searchVisibleInterfaceMethods(
						superInterfaces,
						receiverType,
						scope,
						invocationSite,
						invocationScope,
						onlyStaticMethods,
						methodsFound);
			} else {
				hasPotentialDefaultAbstractMethods = false;
			}
			if(currentType.isParameterizedType()) {
				currentType = ((ParameterizedTypeBinding)currentType).genericType().superclass();
			} else {
				currentType = currentType.superclass();
			}
		}
	}
	private void searchVisibleVariablesAndMethods(
			Scope scope,
			ObjectVector localsFound,
			ObjectVector fieldsFound,
			ObjectVector methodsFound,
			boolean notInJavadoc) {
		
		InvocationSite invocationSite = CompletionEngine.FakeInvocationSite;
		
		boolean staticsOnly = false;
		// need to know if we're in a static context (or inside a constructor)

		Scope currentScope = scope;
		
		done1 : while (true) { // done when a COMPILATION_UNIT_SCOPE is found

			switch (currentScope.kind) {

				case Scope.METHOD_SCOPE :
					// handle the error case inside an explicit constructor call (see MethodScope>>findField)
					MethodScope methodScope = (MethodScope) currentScope;
					staticsOnly |= methodScope.isStatic | methodScope.isConstructorCall;

				case Scope.BLOCK_SCOPE :
					BlockScope blockScope = (BlockScope) currentScope;

					next : for (int i = 0, length = blockScope.locals.length; i < length; i++) {
						LocalVariableBinding local = blockScope.locals[i];

						if (local == null)
							break next;

						if (local.isSecret())
							continue next;

						for (int f = 0; f < localsFound.size; f++) {
							LocalVariableBinding otherLocal =
								(LocalVariableBinding) localsFound.elementAt(f);
							if (CharOperation.equals(otherLocal.name, local.name, true))
								continue next;
						}
						
						localsFound.add(local);
					}
					break;

				case Scope.COMPILATION_UNIT_SCOPE :
					break done1;
			}
			currentScope = currentScope.parent;
		}
		
		staticsOnly = false;
		currentScope = scope;
		
		done2 : while (true) { // done when a COMPILATION_UNIT_SCOPE is found

			switch (currentScope.kind) {
				case Scope.METHOD_SCOPE :
					// handle the error case inside an explicit constructor call (see MethodScope>>findField)
					MethodScope methodScope = (MethodScope) currentScope;
					staticsOnly |= methodScope.isStatic | methodScope.isConstructorCall;
					break;
				case Scope.CLASS_SCOPE :
					ClassScope classScope = (ClassScope) currentScope;
					SourceTypeBinding enclosingType = classScope.referenceContext.binding;
					
					searchVisibleFields(
							enclosingType,
							classScope,
							invocationSite,
							scope,
							staticsOnly,
							notInJavadoc,
							localsFound,
							fieldsFound);
					
					searchVisibleMethods(
							enclosingType,
							classScope,
							invocationSite,
							scope,
							staticsOnly,
							notInJavadoc,
							methodsFound);
					
					staticsOnly |= enclosingType.isStatic();
					break;

				case Scope.COMPILATION_UNIT_SCOPE :
					break done2;
			}
			currentScope = currentScope.parent;
		}
		
		// search in static import
		ImportBinding[] importBindings = scope.compilationUnitScope().imports;
		for (int i = 0; i < importBindings.length; i++) {
			ImportBinding importBinding = importBindings[i];
			if(importBinding.isValidBinding() && importBinding.isStatic()) {
				Binding binding = importBinding.resolvedImport;
				if(binding != null && binding.isValidBinding()) {
					if(importBinding.onDemand) {
						if((binding.kind() & Binding.TYPE) != 0) {
							searchVisibleFields(
									(ReferenceBinding)binding,
									scope,
									invocationSite,
									scope,
									staticsOnly,
									notInJavadoc,
									localsFound,
									fieldsFound);
							
							searchVisibleMethods(
									(ReferenceBinding)binding,
									scope,
									invocationSite,
									scope,
									staticsOnly,
									notInJavadoc,
									methodsFound);
						}
					} else {
						if ((binding.kind() & Binding.FIELD) != 0) {
							searchVisibleFields(
									new FieldBinding[]{(FieldBinding)binding},
									((FieldBinding)binding).declaringClass,
									scope,
									invocationSite,
									scope,
									staticsOnly,
									localsFound,
									fieldsFound);
						} else if ((binding.kind() & Binding.METHOD) != 0) {
							MethodBinding methodBinding = (MethodBinding)binding;
							
							searchVisibleLocalMethods(
									methodBinding.declaringClass.getMethods(methodBinding.selector),
									methodBinding.declaringClass,
									scope,
									invocationSite,
									scope,
									true,
									methodsFound);
						}
					}
				}
			}
		}
	}
}