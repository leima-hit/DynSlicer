package util;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.google.common.base.Verify;

import daikon.PptTopLevel;
import daikon.ProglangType;
import daikon.ValueTuple;
import daikon.VarInfo;
import daikon.VarInfo.VarKind;
import daikon.util.Pair;
import soot.Body;
import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FloatType;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.Modifier;
import soot.NullType;
import soot.PatchingChain;
import soot.PrimType;
import soot.RefLikeType;
import soot.RefType;
import soot.Scene;
import soot.ShortType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.VoidType;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.DefinitionStmt;
import soot.jimple.DoubleConstant;
import soot.jimple.FieldRef;
import soot.jimple.FloatConstant;
import soot.jimple.GotoStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.LongConstant;
import soot.jimple.NullConstant;
import soot.jimple.ParameterRef;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.SwitchStmt;
import soot.jimple.ThisRef;
import soot.jimple.ThrowStmt;
import soot.options.Options;
import soot.tagkit.SourceFileTag;
import soot.tagkit.Tag;
import soot.toolkits.scalar.UnusedLocalEliminator;
import util.DaikonRunner.DaikonTrace;

public class TraceExtractor {
	public static final String pcMethodNameSuffix = "__PC__METHOD";
	public static final String pcMethodArgName = "arg";
	public static final String wrapperMethodNameSuffix = "__WRAPPER__METHOD";
	public static final String instanceWrapperSuffix = "__HASBASE__";
	public static final String assertionMethodName = "my_Assert";

	public Map<soot.Type, SootMethod> assertMethods = new HashMap<soot.Type, SootMethod>();

	private SootMethod newMethod;
	private SootMethod sm;
	private Body body;
	private int newLocalCounter = 0;

	final Stack<String> pcMethodStack = new Stack<String>();
	private boolean haveToPushToPcStack = false;

	public static void main(String[] args) {
		// For testing only!
		TraceExtractor sc = new TraceExtractor();
		DaikonRunner dr = new DaikonRunner();
		System.out.println("Reading dtrace file");
		Set<DaikonTrace> traces = dr.parseDTraceFile("ErrorTestDriver.dtrace.gz");
		System.out.println("Start slicing");
		sc.computeErrorSlices(new File(args[0]), args[1], traces);
	}

	public static int slicerErrors = 0;

	/**
	 * Returns a soot class that contains one method per trace. Each method
	 * contains the sequence of statements executed on that trace.
	 * 
	 * @param classDir
	 * @param classPath
	 * @param traces
	 * @return
	 */
	public SootClass computeErrorSlices(File classDir, String classPath, Collection<DaikonTrace> traces) {

		System.out.println("Computing slices for input: ");
		System.out.println("ClassDir: " + classDir.getAbsolutePath());
		System.out.println("ClassPath: " + classPath);

		loadSootScene(classDir, classPath);

		SootClass myClass = new SootClass("HelloWorld", Modifier.PUBLIC);
		SootClass objClass = Scene.v().getSootClass("java.lang.Object");
		myClass.setSuperclass(objClass);
		Scene.v().addClass(myClass);

		assertMethods.put(RefType.v(), makeAssertMethod(myClass, RefType.v(objClass)));
		assertMethods.put(IntType.v(), makeAssertMethod(myClass, IntType.v()));
		assertMethods.put(FloatType.v(), makeAssertMethod(myClass, FloatType.v()));
		assertMethods.put(DoubleType.v(), makeAssertMethod(myClass, DoubleType.v()));
		assertMethods.put(LongType.v(), makeAssertMethod(myClass, LongType.v()));

		for (DaikonTrace t : traces) {
			try {
				computeErrorSlice(t, myClass);
			} catch (Throwable e) {
				slicerErrors++;
				e.printStackTrace(System.err);
			}
			resetFields();
		}
		return myClass;
	}

	private void resetFields() {
		newMethod = null;
		sm = null;
		body = null;
		newLocalCounter = 0;
		pcMethodStack.clear();
	}

	private SootMethod makeAssertMethod(SootClass myClass, soot.Type type) {
		SootMethod sm = new SootMethod(assertionMethodName, Arrays.asList(new Type[] { type, type }), VoidType.v(),
				Modifier.PUBLIC | Modifier.STATIC);
		myClass.addMethod(sm);
		JimpleBody body = Jimple.v().newBody(sm);
		sm.setActiveBody(body);
		Local l0 = Jimple.v().newLocal("l0", type);
		Local l1 = Jimple.v().newLocal("l1", type);
		body.getLocals().add(l0);
		body.getLocals().add(l1);
		body.getUnits().add(Jimple.v().newIdentityStmt(l0, Jimple.v().newParameterRef(type, 0)));
		body.getUnits().add(Jimple.v().newIdentityStmt(l1, Jimple.v().newParameterRef(type, 1)));
		// done with assert method.
		return sm;
	}

	private DaikonTrace currentTrace;

	/**
	 * For a given trace, create a method that contains the sequqnce of
	 * statements that are executed on that trace.
	 * 
	 * @param trace
	 * @param containingClass
	 */
	public void computeErrorSlice(final DaikonTrace trace, final SootClass containingClass) {
		this.currentTrace = trace;
		 System.err.println("****** All Events");
		 for (Pair<PptTopLevel, ValueTuple> ppt : trace.trace) {
		 System.err.println(ppt.a.name);
		 }
		 System.err.println("****** ");
		try {
			Iterator<Pair<PptTopLevel, ValueTuple>> iterator = trace.trace.iterator();
			SootMethod sm = createTraceMethod(iterator, containingClass);
			addAssertFalseIfNecessary(sm);
			addFakeReturn(sm);

			try {
				sm.getActiveBody().validate();
			} catch (Exception e) {
				System.err.println(e.getMessage());
				System.err.println(newMethod.getActiveBody());
				throw e;
			}

			UnusedLocalEliminator.v().transform(sm.getActiveBody());
			System.err.println(sm.getActiveBody());
		} catch (Throwable e) {
			System.err.println("FAILED TO GENERATE TRACE");
			throw e;
		} 
	}

	/**
	 * Add a fake return statement to the end of a method.
	 * 
	 * @param sm
	 */
	private void addFakeReturn(SootMethod sm) {

		if (sm.getReturnType() instanceof VoidType) {
			sm.getActiveBody().getUnits().add(Jimple.v().newReturnVoidStmt());
		} else if (sm.getReturnType() instanceof RefLikeType || sm.getReturnType() instanceof PrimType) {
			sm.getActiveBody().getUnits().add(Jimple.v().newReturnStmt(getDefaultValue(sm.getReturnType())));
		} else {
			throw new RuntimeException("Not implemented for " + sm.getReturnType());
		}
	}

	private void addAssertFalseIfNecessary(SootMethod sm) {
		Unit lastUnit = sm.getActiveBody().getUnits().getLast();
		boolean requiresAssertFalse = false;
		if (lastUnit instanceof ThrowStmt) {
			sm.getActiveBody().getUnits().removeLast();
			requiresAssertFalse = true;
		} else if (lastUnit instanceof InvokeStmt
				&& ((InvokeStmt) lastUnit).getInvokeExpr() instanceof SpecialInvokeExpr) {
			/*
			 * If the last statement on the trace is a constructor call, we have
			 * to add an assertion.
			 * usually, this assertion is introduced by the instrumenter but
			 * this does not work
			 * for constructor call.
			 */
			// SpecialInvokeExpr sivk =
			// (SpecialInvokeExpr)((InvokeStmt)lastUnit).getInvokeExpr();
			requiresAssertFalse = true;
		}
		if (requiresAssertFalse) {
			sm.getActiveBody().getUnits().add(
					makeAssertNotEquals(sm.getActiveBody().getUnits().getLast(), IntConstant.v(0), IntConstant.v(0)));
		}
	}

	private Value getDefaultValue(soot.Type t) {
		if (t instanceof RefLikeType) {
			return NullConstant.v();
		} else if (t instanceof IntType) {
			return IntConstant.v(0);
		} else if (t instanceof LongType) {
			return LongConstant.v(0);
		} else if (t instanceof FloatType) {
			return FloatConstant.v(0);
		} else if (t instanceof DoubleType) {
			return DoubleConstant.v(0);
		}
		return IntConstant.v(0);
	}

	private Unit copySootStmt(Unit u, Map<Value, Value> substiutionMap) {
		Unit ret = (Unit) u.clone();
		for (ValueBox vb : ret.getUseAndDefBoxes()) {
			if (substiutionMap.containsKey(vb.getValue())) {
				vb.setValue(substiutionMap.get(vb.getValue()));
			}
		}
		ret.addAllTagsOf(u);
		for (Tag t : this.sm.getDeclaringClass().getTags()) {
			if (t instanceof SourceFileTag) {
				ret.addTag(t);
			}
		}
		return ret;
	}

	private SootMethod createNewMethod(final SootMethod orig, final SootClass containingClass) {
		final String method_prefix = "XY_";
		SootMethod newMethod = new SootMethod(method_prefix + orig.getName(), orig.getParameterTypes(),
				orig.getReturnType(), orig.getModifiers());
		containingClass.addMethod(newMethod);
		JimpleBody newBody = Jimple.v().newBody(newMethod);
		newMethod.setActiveBody(newBody);
		return newMethod;
	}

	private SootMethod createTraceMethod(Iterator<Pair<PptTopLevel, ValueTuple>> iterator,
			final SootClass containingClass) {
		// final List<Unit> sootTrace = new LinkedList<Unit>();
		final Stack<SootMethod> methodStack = new Stack<SootMethod>();
		final Stack<Unit> callStack = new Stack<Unit>();

		pcMethodStack.clear();

		Pair<PptTopLevel, ValueTuple> ppt = iterator.next();
		Verify.verify(ppt.a.name.endsWith(":::ENTER"), "Ppt is not a procedure entry: " + ppt.a.name);

		final Map<Value, Value> substiutionMap = new HashMap<Value, Value>();

		sm = findMethodForPpt(ppt.a);
		// get the active body and start adding to it.
		newMethod = createNewMethod(sm, containingClass);
		final Body newBody = newMethod.getActiveBody();

		enterMethod(ppt, methodStack, callStack, newBody, substiutionMap);

		while (iterator.hasNext()) {
			ppt = iterator.next();

			boolean exceptionalJump = false;
			boolean justPushedOnCallStack = false;

			if (ppt.a.name.contains(pcMethodNameSuffix) && ppt.a.name.endsWith(":::ENTER")
					&& ppt.a.name.contains("clinit") && !sm.getName().equals("<clinit>")) {
				//check if we just jumped into a static initializer.
				final String cName = ppt.a.name.substring(0, ppt.a.name.indexOf("._la_clinit_ra"));
				SootClass sc = Scene.v().getSootClass(cName);
				SootMethod staticInitializer = sc.getMethodByName("<clinit>");
				sm = staticInitializer;
				body = staticInitializer.retrieveActiveBody();
				methodStack.push(staticInitializer);
				Unit cinitInvoke = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(staticInitializer.makeRef()));
				callStack.push(cinitInvoke);
				haveToPushToPcStack = true;
				for (SootField field : sm.getDeclaringClass().getFields()) {
					if (field.isStatic()) {
						Value rhs = getDefaultValue(field.getType());
						Value lhs = Jimple.v().newStaticFieldRef(field.makeRef());
						Unit init = Jimple.v().newAssignStmt(lhs, rhs);
						newBody.getUnits().add(copySootStmt(init, substiutionMap));
					}
				}
				for (Local l : body.getLocals()) {
					if (!substiutionMap.containsKey(l)) {
						if (l.getType() instanceof NullType) {
							System.out.println("ignoring local " + l.getName() + " because it doesn't have a type");
							continue;
						}
						Local newLocal = Jimple.v().newLocal(sm.getName() + "_" + l.getName(), l.getType());
						substiutionMap.put(l, newLocal);
						newMethod.getActiveBody().getLocals().add(newLocal);
					}
				}				
//TODO				
			}
			
			/*
			 * If the ppt points to a program point that is not in the current method
			 * and not a static initializer, we assume that this method just threw an
			 * exception and we have to find the method on the method stack where the
			 * exception got caught
			 */
			if (ppt.a.name.contains(pcMethodNameSuffix) && ppt.a.name.endsWith(":::ENTER")) {
				/**
				 * =============================================================
				 * ===
				 * This part handles exceptional back jumps
				 */
				if (haveToPushToPcStack) {
					pcMethodStack.push(ppt.a.name);
					haveToPushToPcStack = false;
				} else {
					if (!pcMethodStack.isEmpty() && !ppt.a.name.equals(pcMethodStack.peek())) {
						// then there was an exception and we have to pop stuff
						// from our stacks until we have the right method again.
						while (!pcMethodStack.isEmpty() && !ppt.a.name.equals(pcMethodStack.peek())) {
							if (!callStack.isEmpty()) {
								callStack.pop();
								// I dont think we have to do this because
								// jimple already takes care of that.
								// if (callee instanceof InvokeStmt &&
								// ((InvokeStmt)callee).getInvokeExpr()
								// instanceof SpecialInvokeExpr) {
								// //if we leave a constructor with an exception
								// we have to set the
								// //corresponding var to null again.
								// SpecialInvokeExpr ivk =
								// (SpecialInvokeExpr)((InvokeStmt)callee).getInvokeExpr();
								// if (ivk.getMethod().isConstructor() &&
								// !ivk.getMethod().isStatic()) {
								// //not sure if the condition above is
								// necessary.
								// Unit asn =
								// copySootStmt(Jimple.v().newAssignStmt(ivk.getBase(),
								// NullConstant.v()), substiutionMap);
								// asn.addAllTagsOf(callee);
								// newBody.getUnits().add(asn);
								// }
								// }
							}
							methodStack.pop();
							pcMethodStack.pop();
						}
						if (!pcMethodStack.isEmpty()) {
							sm = methodStack.peek();
							body = sm.retrieveActiveBody();
							exceptionalJump = true;
						} else {
							return newMethod;
						}
					}
				}
				/**
				 * =============================================================
				 * ===
				 */

				VarInfo vi = ppt.a.find_var_by_name(pcMethodArgName);
				long arg = (Long) ppt.b.getValueOrNull(vi);

				// skip the exit of this method as well.
				ppt = iterator.next();

				List<Integer> skipList = new LinkedList<Integer>();
				Unit u = findUnitAtPos(body, arg, skipList);
				// Unit u= findUnitAtPos(body, arg, iterator);
				// System.err.println(" " + u + "\t" + sm.getName());

				if (exceptionalJump) {
					// get the caughtexceptionref
					Unit pre = u;
					while (pre != null) {
						if (pre instanceof DefinitionStmt
								&& ((DefinitionStmt) pre).getRightOp() instanceof CaughtExceptionRef) {
							break;
						}
						pre = sm.getActiveBody().getUnits().getPredOf(pre);
					}
					if (pre != null) {
						CaughtExceptionRef cer = (CaughtExceptionRef) (((DefinitionStmt) pre).getRightOp());
						// check if the last element of the newbody is a throw.
						// if so, remove it and use its op for the assignment
						Unit last = newBody.getUnits().getLast();
						if (last instanceof ThrowStmt) {
							newBody.getUnits().removeLast();
							Unit newasn = Jimple.v().newAssignStmt(((DefinitionStmt) pre).getLeftOp(),
									((ThrowStmt) last).getOp());
							newBody.getUnits().add(copySootStmt(newasn, substiutionMap));
						} else {

							// create a new runtimeexception here.
							final String name = "__exLocal" + newBody.getLocalCount();
							RefType t = (RefType) cer.getType();
							if (t.getSootClass().isAbstract()) {
								t = RefType.v(Scene.v().getSootClass("java.lang.RuntimeException"));
							}
							Local exVar = Jimple.v().newLocal(name, t);
							newBody.getLocals().add(exVar);
							Stmt s = Jimple.v().newAssignStmt(exVar, Jimple.v().newNewExpr(t));
							s.addAllTagsOf(pre);
							newBody.getUnits().add(s);

							SootMethod constr = t.getSootClass().getMethod("<init>", new LinkedList<Type>(),
									VoidType.v());
							s = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(exVar, constr.makeRef()));
							s.addAllTagsOf(pre);
							newBody.getUnits().add(s);

							Unit newasn = Jimple.v().newAssignStmt(((DefinitionStmt) pre).getLeftOp(), exVar);
							newBody.getUnits().add(copySootStmt(newasn, substiutionMap));
						}
					} else {
						// TODO
						System.err.println("No catch found for " + u + ". Guess we are done here.");
						return newMethod;
					}
				}

				if (u == null) {
					// TODO:
					continue;
				}
				for (ValueBox vb : u.getUseAndDefBoxes()) {
					if (vb.getValue() instanceof FieldRef) {
						if (!((FieldRef) vb.getValue()).getField().isPublic()) {
							if (((FieldRef) vb.getValue()).getField().isStatic()) {
								((FieldRef) vb.getValue()).getField().setModifiers(Modifier.PUBLIC | Modifier.STATIC);
							} else {
								((FieldRef) vb.getValue()).getField().setModifiers(Modifier.PUBLIC);
							}
						}
					}

				}
				if (u instanceof IfStmt || u instanceof SwitchStmt || u instanceof GotoStmt) {
					// ignore
				} else if (u instanceof ReturnVoidStmt) {
					// do nothing
					if (callStack.isEmpty()) {
						newBody.getUnits().add(copySootStmt(u, substiutionMap));
					} else {
						callStack.pop();
					}
				} else if (u instanceof ReturnStmt) {
					ReturnStmt rstmt = (ReturnStmt) u;
					if (callStack.isEmpty()) {
						newBody.getUnits().add(copySootStmt(u, substiutionMap));
					} else {
						Unit callee = callStack.pop();
						if (callee instanceof DefinitionStmt) {
							DefinitionStmt call = (DefinitionStmt) callee;
							Unit asn = copySootStmt(Jimple.v().newAssignStmt(call.getLeftOp(), rstmt.getOp()),
									substiutionMap);
							asn.addAllTagsOf(rstmt);// keep the line number of
													// the return
							newBody.getUnits().add(asn);
						} else if (callee instanceof InvokeStmt) {
							// if the callee was an InvokeStmt, the return value
							// is
							// ignored.
						} else {
							throw new RuntimeException("Not implemented " + callee + "\n" + ppt.a.name);
						}
					}
				} else if (((Stmt) u).containsInvokeExpr()) {
					InvokeExpr ivk = ((Stmt) u).getInvokeExpr();

					if (ivk.getMethod().getDeclaringClass().isLibraryClass()
							|| ivk.getMethod().getDeclaringClass().isJavaLibraryClass()) {
						// do not try to inline library calls.
						newBody.getUnits().add(copySootStmt(u, substiutionMap));
					} else {
						callStack.push(u);
						justPushedOnCallStack = true;
					}
				} else {
					newBody.getUnits().add(copySootStmt(u, substiutionMap));
				}

				for (int i : skipList) {
					if (!iterator.hasNext()) {
						// Then the trace just threw an exception and we're
						// done.
						return newMethod;
					}
					ppt = iterator.next();
					Verify.verify(ppt.a.name.contains(pcMethodNameSuffix) && ppt.a.name.endsWith(":::ENTER"));
					vi = ppt.a.find_var_by_name(pcMethodArgName);
					arg = (Long) ppt.b.getValueOrNull(vi);
					Verify.verify(arg == (long) i, "Wrong number " + arg + "!=" + i);
					ppt = iterator.next();
					Verify.verify(ppt.a.name.contains(pcMethodNameSuffix) && ppt.a.name.contains(":::EXIT"));
				}
				if (!iterator.hasNext() && justPushedOnCallStack) {
					/*
					 * In this case we just put an InstanceInvoke on the call
					 * stack
					 * but the base was null and thus it fired an exception and
					 * ended
					 * the trace. This is a bit hacky, I guess.
					 */
					callStack.pop();
					InstanceInvokeExpr ivk = (InstanceInvokeExpr) (((Stmt) u).getInvokeExpr());
					Value v1 = ivk.getBase();
					Value v2 = NullConstant.v();
					Unit asrt = makeAssertNotEquals(u, v1, v2);
					asrt = copySootStmt(asrt, substiutionMap);
					newBody.getUnits().add(asrt);
					return newMethod;
				}
			} else if (ppt.a.name.contains(wrapperMethodNameSuffix) && ppt.a.name.endsWith(":::ENTER")) {
				Pair<PptTopLevel, ValueTuple> next = peekNextPpt(ppt);
				Unit call = callStack.pop();
				SootMethod callee = ((Stmt) call).getInvokeExpr().getMethod();
				if (next != null && next.a.name.contains(wrapperMethodNameSuffix) && next.a.name.contains(":::EXIT")) {
					Pair<PptTopLevel, ValueTuple> pre = ppt;
					ppt = iterator.next();
					Set<VarInfo> changedVars = findChangedVariables(pre, ppt);
					for (VarInfo vi : changedVars) {
						throw new RuntimeException("Not implementd ");// TODO
																		// side
																		// effects.
					}
					// update the return value.
					if (call instanceof DefinitionStmt) {
						VarInfo retVi = null;
						for (VarInfo vi : ppt.a.var_infos) {
							if (vi.var_kind == VarKind.RETURN) {
								retVi = vi;
								break;
							}
						}
						if (retVi != null) {
							Value rhs = daikonValueToSootValue(retVi, ppt, newBody);
							Unit asn = Jimple.v().newAssignStmt(((DefinitionStmt) call).getLeftOp(), rhs);
							asn.addAllTagsOf(call);
							newBody.getUnits().add(copySootStmt(asn, substiutionMap));
						} else {
							System.err.println(
									"Could not find return var for " + ppt.a.name + " ignoring the statement.");
							for (VarInfo vi : ppt.a.var_infos) {
								System.err.println("\t" + vi.toString());
							}
						}
					}
				} else if (next == null) {
					// TODO:--------------------- check if next==null is the
					// right condition
					// Wrapped method threw an exception ...
					int offset = 0;
					if (callee.getName().contains(instanceWrapperSuffix)) {
						offset = 1;
						Value v1 = ((Stmt) call).getInvokeExpr().getArg(0);
						Value v2 = NullConstant.v();
						Unit asrt = makeAssertNotEquals(call, v1, v2);
						asrt.addAllTagsOf(call);
						asrt = copySootStmt(asrt, substiutionMap);
						newBody.getUnits().add(asrt);
					} else {
						// dont do anything.
					}
					// TODO: assert that the current input is illegal.
					// throw new RuntimeException("throws an ex "+ppt.a.name);
					for (int i = offset; i < ((Stmt) call).getInvokeExpr().getArgCount(); i++) {
						Value v1 = ((Stmt) call).getInvokeExpr().getArg(i);
						VarInfo argVar = ppt.a.find_var_by_name("arg" + i);
						Value v2 = daikonValueToSootValue(argVar, ppt, newBody);
						if (v1.equals(v2)) {
							System.err.println("Not adding " + v1 + "!=" + v2 + " beause its trivial");
						} else {
							Unit asrt = makeAssertNotEquals(call, v1, v2);
							asrt.addAllTagsOf(call);
							newBody.getUnits().add(copySootStmt(asrt, substiutionMap));
						}
						// System.err.println("adding assertion " + asrt);
					}
					if (next == null) {
						return newMethod;
					}
				}
			} else if (ppt.a.name.endsWith(":::ENTER")) {
				enterMethod(ppt, methodStack, callStack, newBody, substiutionMap);
			} else if (ppt.a.name.contains(":::EXIT")) {
				SootMethod exitedMethod = methodStack.pop();
				sm = methodStack.peek();
				body = sm.retrieveActiveBody();
				Verify.verify(!pcMethodStack.isEmpty(), "pcMethodStack must not be empty when goign from "
						+ exitedMethod.getName() + " to " + sm.getName());
				pcMethodStack.pop();
			} else {
				System.err.println("Don't know how to handle " + ppt.a.name);
			}
		}

		return newMethod;
	}

	public Unit makeAssertNotEquals(Unit host, Value v1, Value v2) {
		soot.Type assertType = v1.getType();
		if (assertType instanceof RefLikeType) {
			assertType = RefType.v();
		}
		SootMethod assertMethod = assertMethods.get(assertType);
		Verify.verifyNotNull(assertMethod, "No method of " + v1.getType());
		// TODO: something goes wrong with the locals here
		Unit asrt = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(assertMethod.makeRef(), v1, v2));
		asrt.addAllTagsOf(host);
		return asrt;
	}

	private Value daikonValueToSootValue(VarInfo vi, Pair<PptTopLevel, ValueTuple> ppt, Body newBody) {
		Object val = ppt.b.getValueOrNull(vi);
		if (val == null) {
			return NullConstant.v();
		} else if (vi.type == ProglangType.INT || vi.type == ProglangType.BOOLEAN || vi.type == ProglangType.CHAR) {
			return IntConstant.v(((Long) val).intValue());
		} else if (vi.type == ProglangType.LONG_PRIMITIVE) {
			return LongConstant.v((Long) val);
		} else if (vi.type == ProglangType.DOUBLE) {
			return DoubleConstant.v((Double) val);
		} else if (vi.type == ProglangType.STRING) {
			VarInfo vi_str = ppt.a.find_var_by_name(vi.name() + ".toString");
			val = ppt.b.getValueOrNull(vi_str);
			return StringConstant.v(String.valueOf(val));
		}

		// System.err.println(this.sm.getName()+ " "+vi.name() + " " + vi.type +
		// " " + val);
		// System.err.println(vi);
		if (Scene.v().containsClass(vi.type.toString())) {
			SootClass sc = Scene.v().loadClass(vi.type.toString(), SootClass.SIGNATURES);
			Local newlocal = Jimple.v().newLocal("newLocal_" + (newLocalCounter++), RefType.v(sc));
			newBody.getLocals().add(newlocal);
			newBody.getUnits().add(Jimple.v().newAssignStmt(newlocal, Jimple.v().newNewExpr(RefType.v(sc))));
			// TODO ------------------
			return newlocal;
		}

		throw new RuntimeException("not implemented for type " + vi.type + " of value " + val);
	}

	private Pair<PptTopLevel, ValueTuple> peekNextPpt(Pair<PptTopLevel, ValueTuple> ppt) {
		return peekNextPpt(ppt, 0);
	}

	private Pair<PptTopLevel, ValueTuple> peekNextPpt(Pair<PptTopLevel, ValueTuple> ppt, int offset) {
		int currentPos = this.currentTrace.trace.indexOf(ppt);
		ListIterator<Pair<PptTopLevel, ValueTuple>> lit = this.currentTrace.trace.listIterator(currentPos + 1 + offset);
		if (lit.hasNext()) {
			return lit.next();
		}
		return null;
	}

	private Set<VarInfo> findChangedVariables(Pair<PptTopLevel, ValueTuple> pre, Pair<PptTopLevel, ValueTuple> post) {
		Set<VarInfo> changedVars = new HashSet<VarInfo>();
		for (VarInfo a : pre.a.var_infos) {
			for (VarInfo b : post.a.var_infos) {
				if (a.equals(b)) {
					Object v1 = pre.b.getValueOrNull(a);
					Object v2 = post.b.getValueOrNull(a);
					if (v1 == null && v2 == null) {
						// nothing changed; ignore
					} else if (v1 != null && v1.equals(v2)) {
						// nothing changed; ignore
					} else {
						// value changed. remember update.
						System.err.println("Var " + a.name() + " changed from " + v1 + " to " + v2);
						changedVars.add(b);
					}
				}
			}
		}
		return changedVars;
	}

	// private void findChangedVariables(Pair<PptTopLevel, ValueTuple> ppt, Unit
	// u, int skipListSize) {
	// // TODO: +3 seems to be a correct offset, because there might be one
	// // enter and exit after
	// // the ppt ... however, magic constants are always bad so find a better
	// // way to do this.
	// int currentPos = this.currentTrace.trace.indexOf(ppt) + 3 + skipListSize;
	// if (currentPos == this.currentTrace.trace.size() - 1) {
	// throw new RuntimeException("Not implemented");
	// }
	// ListIterator<Pair<PptTopLevel, ValueTuple>> lit =
	// this.currentTrace.trace.listIterator(currentPos);
	// if (lit.hasNext()) {
	// Pair<PptTopLevel, ValueTuple> next_ppt = lit.next();
	// final String next_label = next_ppt.a.name;
	// // check if the next program point is a statement
	// // in another procedure. In that case, this point threw an
	// // exception.
	//
	// if (next_label.endsWith(":::ENTER") && !pcMethodStack.isEmpty()
	// && !next_label.equals(pcMethodStack.peek())) {
	// // throw new RuntimeException("Lib function throw an exception:
	// // " + u);
	// } else {
	// findChangedVariables(ppt, next_ppt);
	// }
	// // TODO
	// } else {
	// throw new RuntimeException("Not implemented");
	// }
	// }

	private void enterMethod(Pair<PptTopLevel, ValueTuple> ppt, final Stack<SootMethod> methodStack,
			final Stack<Unit> callStack, Body newBody, final Map<Value, Value> substiutionMap) {
		sm = findMethodForPpt(ppt.a);

		body = sm.retrieveActiveBody();
		methodStack.push(sm);
		haveToPushToPcStack = true;
		// Add all the locals from sm to newMethod
		for (Local l : body.getLocals()) {
			if (!substiutionMap.containsKey(l)) {
				if (l.getType() instanceof NullType) {
					System.out.println("ignoring local " + l.getName() + " because it doesn't have a type");
					continue;
				}
				Local newLocal = Jimple.v().newLocal(sm.getName() + "_" + l.getName(), l.getType());
				substiutionMap.put(l, newLocal);
				newMethod.getActiveBody().getLocals().add(newLocal);
			}
		}

		// Add the IdentityStmts that assign Parameters to Locals
		// because those do not show up on the trace otherwise.
		for (Unit u : body.getUnits()) {
			if (u instanceof IdentityStmt && ((IdentityStmt) u).getRightOp() instanceof ParameterRef) {
				IdentityStmt idStmt = (IdentityStmt) u;
				ParameterRef pr = (ParameterRef) idStmt.getRightOp();
				if (!callStack.isEmpty()) {
					InvokeExpr ivk = ((Stmt) callStack.peek()).getInvokeExpr();
					Verify.verify(ivk.getMethod().getName().equals(sm.getName()),
							ivk.getMethod().getName() + "!=" + sm.getName());
					// Now we have to add and AssignStmt instead of a
					// DefinitionStmt.
					try {
						Unit s = copySootStmt(Jimple.v().newAssignStmt(idStmt.getLeftOp(), ivk.getArg(pr.getIndex())),
								substiutionMap);
						s.addAllTagsOf(sm);
						// find the line number of the first stmt
						s.addAllTagsOf(((JimpleBody) body).getFirstNonIdentityStmt());
						newBody.getUnits().add(s);
					} catch (Exception e) {
						System.err.println("Failed to inline call");
						System.err.println("In method " + sm.getSignature());
						System.err.println(u);
						System.err.println(callStack.peek());
						e.printStackTrace(System.err);
						throw new RuntimeException();
					}
				} else {
					newBody.getUnits().add(copySootStmt(u, substiutionMap));
				}
			} else if (u instanceof IdentityStmt && ((IdentityStmt) u).getRightOp() instanceof ThisRef) {
				IdentityStmt idStmt = (IdentityStmt) u;
				if (!callStack.isEmpty() && ((Stmt) callStack.peek()).getInvokeExpr() instanceof InstanceInvokeExpr) {
					InstanceInvokeExpr ivk = (InstanceInvokeExpr) ((Stmt) callStack.peek()).getInvokeExpr();
					Stmt s = Jimple.v().newAssignStmt(idStmt.getLeftOp(), ivk.getBase());
					newBody.getUnits().add(copySootStmt(s, substiutionMap));
				} else {
					newBody.getUnits().add(copySootStmt(u, substiutionMap));
				}
			}
		}

		// init all fields if its a constructor
		if (sm.isConstructor()) {
			for (SootField field : sm.getDeclaringClass().getFields()) {
				if (!field.isStatic()) {
					Value rhs = getDefaultValue(field.getType());
					Value lhs = Jimple.v().newInstanceFieldRef(body.getThisLocal(), field.makeRef());
					Unit init = Jimple.v().newAssignStmt(lhs, rhs);
					newBody.getUnits().add(copySootStmt(init, substiutionMap));
				}
			}
		} else if (sm.isStaticInitializer()) {
			for (SootField field : sm.getDeclaringClass().getFields()) {
				if (field.isStatic()) {
					Value rhs = getDefaultValue(field.getType());
					Value lhs = Jimple.v().newStaticFieldRef(field.makeRef());
					Unit init = Jimple.v().newAssignStmt(lhs, rhs);
					newBody.getUnits().add(copySootStmt(init, substiutionMap));
				}
			}
		}

	}

	private Unit findUnitAtPos(Body body, long pos, List<Integer> outSkipList) {
		PatchingChain<Unit> units = body.getUnits();
		Unit ret = null;
		for (Unit u : units) {
			if (u instanceof InvokeStmt) {
				InvokeStmt ivk = (InvokeStmt) u;
				InvokeExpr ie = ivk.getInvokeExpr();
				List<Value> args = ie.getArgs();

				if (isPcMethod(ivk) && (((IntConstant) args.get(0)).value == (int) pos)) {
					Unit next = units.getSuccOf(u);
					while (isPcMethod(next) && next != null) {
						Value v = ((InvokeStmt) next).getInvokeExpr().getArg(0);
						outSkipList.add(((IntConstant) v).value);
						next = units.getSuccOf(next);
					}
					return next;
				}
			}
		}
		return ret;
	}

	private boolean isPcMethod(Unit u) {
		if (u instanceof InvokeStmt) {
			InvokeStmt ivk = (InvokeStmt) u;
			return ivk.getInvokeExpr().getMethod().getName().contains(pcMethodNameSuffix);
		}
		return false;
	}

	private SootMethod findMethodForPpt(PptTopLevel ppt) {
		final String qualifiedMethodName = ppt.name.substring(0, ppt.name.indexOf("("));
		final String className = qualifiedMethodName.substring(0, qualifiedMethodName.lastIndexOf('.'));
		String methodName = qualifiedMethodName.substring(qualifiedMethodName.lastIndexOf('.') + 1,
				qualifiedMethodName.length());

		if (!Scene.v().containsClass(className)) {
			throw new RuntimeException("Class not in scene: " + className);
		}
		SootClass sc = Scene.v().getSootClass(className);
		if (className.endsWith(methodName)) {
			// constructor call.
			methodName = "<init>";
		}

		final String paramSig = ppt.name.substring(ppt.name.indexOf("(") + 1, ppt.name.indexOf(":::") - 1).replace(" ",
				"");
		List<soot.Type> paramTypes = new LinkedList<soot.Type>();
		if (paramSig != null && paramSig.length() > 0) {
			for (String paramName : paramSig.split(",")) {
				soot.Type t = stringToType(paramName);
				paramTypes.add(t);
			}
		}
		SootMethod sm = sc.getMethod(methodName, paramTypes);
		return sm;
	}

	private soot.Type stringToType(String s) {
		soot.Type t;
		if (s.endsWith("[]")) {
			return stringToType(s.substring(0, s.length() - 2)).makeArrayType();
		}
		if ("int".equals(s)) {
			t = IntType.v();
		} else if ("float".equals(s)) {
			t = FloatType.v();
		} else if ("double".equals(s)) {
			t = DoubleType.v();
		} else if ("long".equals(s)) {
			t = LongType.v();
		} else if ("char".equals(s)) {
			t = CharType.v();
		} else if ("boolean".equals(s)) {
			t = BooleanType.v();
		} else if ("short".equals(s)) {
			t = ShortType.v();
		} else if ("byte".equals(s)) {
			t = ByteType.v();
		} else {
			t = Scene.v().getRefType(s);
		}
		return t;
	}

	private void loadSootScene(File classDir, String classPath) {
		soot.G.reset();
		Options sootOpt = Options.v();
		// general soot options
		sootOpt.set_keep_line_number(true);
		sootOpt.set_allow_phantom_refs(true);
		sootOpt.set_prepend_classpath(true); // -pp
		sootOpt.set_output_format(Options.output_format_none);
		sootOpt.set_src_prec(Options.src_prec_class);

		sootOpt.set_java_version(Options.java_version_1_8);
		sootOpt.set_asm_backend(true);

		if (!classPath.contains(classDir.getAbsolutePath())) {
			classPath += File.pathSeparator + classDir.getAbsolutePath();
		}
		sootOpt.set_soot_classpath(classPath);

		List<String> processDirs = new LinkedList<String>();
		processDirs.add(classDir.getAbsolutePath());
		sootOpt.set_process_dir(processDirs);

		sootOpt.setPhaseOption("jb.a", "enabled:false");
		sootOpt.setPhaseOption("jop.cpf", "enabled:false");
		sootOpt.setPhaseOption("jop.cfg", "enabled:true");
		sootOpt.setPhaseOption("jb", "use-original-names:true");

		// Scene.v().loadClassAndSupport("java.lang.System");
		// Scene.v().loadClassAndSupport("java.lang.Thread");
		// Scene.v().loadClassAndSupport("java.lang.ThreadGroup");

		Scene.v().loadBasicClasses();
		Scene.v().loadNecessaryClasses();

		// for (String s :
		// dynslicer.Main.getClasses(classDir.getAbsolutePath())) {
		// if (!Scene.v().containsClass(s)) {
		// try {
		// System.out.print("Loading " + s);
		// SootClass sc = Scene.v().loadClass(s, SootClass.SIGNATURES);
		// sc.setApplicationClass();
		// Scene.v().addClass(sc);
		// System.out.println("... done");
		// } catch (Exception e) {
		// e.printStackTrace(System.err);
		// }
		// }
		// }

		// for (SootClass sc : Scene.v().getClasses())
		// System.err.println(sc.getName());

		for (SootClass sc : Scene.v().getClasses()) {
			if (sc.resolvingLevel() < SootClass.SIGNATURES) {
				sc.setResolvingLevel(SootClass.SIGNATURES);
			}
		}

	}

}
