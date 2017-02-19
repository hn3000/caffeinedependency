package de.niesche.java.dependencies;

import java.io.File;
import java.io.FileFilter;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Name;


class FileWalker implements Iterator<File> {
	FileWalker(File baseDir, FileFilter filter) {
		if (!baseDir.exists() || !baseDir.isDirectory()) {
			throw new IllegalArgumentException("expected baseDir to be an existing directory");
		}
		_dirQueue = new ArrayList<>();
		_fileQueue = new ArrayList<>();
		_dirQueue.add(baseDir);
		_baseDir = baseDir;
		_filter = filter;
	}

	@Override
	public boolean hasNext() {
		_refill();
		return !_fileQueue.isEmpty();
	}
	
	@Override
	public File next() {
		if (hasNext()) {
			return _fileQueue.remove(0);
		}
		throw new NoSuchElementException("no more files to walk");
	}
	
	
	private void _refill() {
		while (0 == _fileQueue.size() && 0 != _dirQueue.size()) {
			File nextDir = _dirQueue.remove(0);
			String names[] = nextDir.list();
			for (String name : names) {
				File file = new File(nextDir, name);
				if (file.isDirectory()) {
					_dirQueue.add(file);
				} else if (null == _filter || _filter.accept(file)) {
					_fileQueue.add(file);
				}
			}
		}
	}




	File _baseDir;
	FileFilter _filter;
	List<File> _dirQueue;
	List<File> _fileQueue;
}

public class JavaDependencyParser {
	
	private interface IHandler {
		void start(String project);
		void handleFile(String fn, CompilationUnit cu, boolean hasNext);
		void end(String project);
	}
	
	private static class PrinterSimple implements IHandler {
		PrinterSimple(PrintStream out) {
			_out = out;
		}
		@Override
		public void start(String project) { }
		@Override
		public void end(String project) { }
		
		@Override
		public void handleFile(String fn, CompilationUnit unit, boolean hasNext) {
			Optional<PackageDeclaration> maybePackage = unit.getPackageDeclaration();
			NodeList<TypeDeclaration<?>> types = unit.getTypes();
			types.forEach((x) -> { if(x.isPublic()) _out.print((maybePackage.isPresent() ? maybePackage.get().getNameAsString() : "") + "." + x.getName()+" "); });
			_out.print("<- ");
			NodeList<ImportDeclaration> imports = unit.getImports();
			imports.forEach((x) -> _out.print(x.getName()+" "));
			_out.println();
		}
		
		PrintStream _out;
	}

	private static class PrinterJson implements IHandler {
		PrinterJson(PrintStream out) {
			_out = out;
		}
		public void start(String project) {
			_out.println("{");
			_out.println("  \"project\": \""+project+"\"");
			_out.println("  \"files\": [");
		}
		public void handleFile(String fn, CompilationUnit unit, boolean hasNext) {
			_out.println("{");
			_out.println("  \"file\": \""+fn+"\",");

			Optional<PackageDeclaration> maybePackage = unit.getPackageDeclaration();
			String packagePrefix = (maybePackage.isPresent() ? maybePackage.get().getNameAsString() + "." : "");

			NodeList<TypeDeclaration<?>> types = unit.getTypes();
			String publicType = null;

			_out.println("  \"types\": [");
			Iterator<TypeDeclaration<?>> iTypes = types.iterator();
			while (iTypes.hasNext()) {
				TypeDeclaration<?> typeDeclaration = iTypes.next();
				String thisType =  packagePrefix + typeDeclaration.getName();
				if (typeDeclaration.isPublic()) {
					publicType = "\""+thisType+"\"";
				}
				_out.println("    \""+thisType+"\""+(iTypes.hasNext()?",":""));
			}
			_out.println("  ],");
			_out.println("  \"publicType\": "+publicType+",");

			_out.println("  \"imports\": [");
			NodeList<ImportDeclaration> imports = unit.getImports();
			Iterator<ImportDeclaration> iImports = imports.iterator();

			while (iImports.hasNext()) {
				ImportDeclaration typeDeclaration = iImports.next();
				Name thisImport =  typeDeclaration.getName();
				_out.println("    \""+thisImport+"\""+(iImports.hasNext()?",":""));
			}

			
			_out.println("  ]");

			_out.println("}"+(hasNext?",":""));
		}
		public void end(String project) {
			_out.println("]}");
		}
		PrintStream _out;
	}

	private static class TypeEntry {
		TypeEntry(String name) {
			this.name = name;
		}
		String name;
		List <String> filenames = new ArrayList<>();
		Set<String> imports = new HashSet<>();

		int id = Integer.MAX_VALUE;
		int lowestReachable = Integer.MAX_VALUE;
		boolean inStack = false;
		
		@Override
		public String toString() {
			return "TypeEntry "+id+":"+name;
		}
	}
	
	private static class CycleFinder implements IHandler {
		public CycleFinder(boolean csv) {
			//_files = new ArrayList<>();
			_types = new HashMap<>();
			_wildCardsFound = false;
			_csv = csv;
		}
		
		@Override
		public void start(String project) {
			_types = new HashMap<>();
			_wildCardsFound = false;
		}
		@Override
		public void handleFile(String fn, CompilationUnit cu, boolean hasNext) {
			//_files.add(cu);
			
			Set<String> imports = new HashSet<>();
			for (ImportDeclaration i : cu.getImports()) {
				if (i.isAsterisk()) {
					_wildCardsFound = true;
				} else {
					String imp = i.getNameAsString();
					imports.add(imp);
				}
			}
			
			Optional<PackageDeclaration> maybePackage = cu.getPackageDeclaration();
			String packagePrefix = (maybePackage.isPresent() ? maybePackage.get().getNameAsString() + "." : "");
			for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
				String name = packagePrefix+typeDecl.getNameAsString();
				TypeEntry type = _types.get(name);
				if (null == type) {
					type = new TypeEntry(name);
					_types.put(name, type);
				}
				type.filenames.add(fn);
				type.imports.addAll(imports);
			}

		}
		@Override
		public void end(String project) {
			findCycles();
			int maxCycle = _cycles.stream().mapToInt(List::size).reduce(0, Integer::max);
			int numInCycle = _cycles.stream().mapToInt(List::size).reduce(0, Integer::sum);
			int numCycles = _cycles.size();
			int numTypes = _types.size();

			String json = cyclesAsJson();
			if (_csv) {
				json = json.replaceAll("\"", "\\\"");
				System.out.println(project+","+numCycles+","+numInCycle+","+maxCycle+","+numTypes+","+_wildCardsFound+",\""+json+"\"");
			} else {
				System.out.println(project+": found "+numInCycle+" types in "+numCycles+" strongly connected components (largest is "+maxCycle+") after looking at "+numTypes+" types");
				System.out.println(json);
				if (_wildCardsFound) {
					System.out.println("Note: Found wildcard imports, result is a lower bound.");
				}
			}
			

			
		}
		
		String cyclesAsJson() {
			StringBuilder result = new StringBuilder();
			String linesep = "";
			String indent = "";
			if (!_csv) {
				linesep = System.lineSeparator();
				indent = "  ";
			}
			
			result.append("[");
			result.append(linesep);
			boolean firstCycle = true;
			for (List<TypeEntry> list : _cycles) {
				result.append(linesep);
				result.append(indent);
				if (firstCycle) {
					firstCycle = !firstCycle;
				} else {
					result.append(',');
				}
				result.append("[");
				boolean firstEntry = true;
				for (TypeEntry typeEntry : list) {
					result.append(linesep);
					result.append(indent);
					result.append(indent);
					if (firstEntry) {
						firstEntry = !firstEntry;
					} else {
						result.append(',');
					}
					result.append('"');
					result.append(typeEntry.name);
					result.append('"');
				}
				result.append(linesep);
				result.append(indent);
				result.append("]");
			}
			result.append(linesep);
			result.append("]");
			
			return result.toString();
		}
		
		void findCycles() {
			for (TypeEntry t : _types.values()) {
				t.id = t.lowestReachable = Integer.MAX_VALUE;
				t.inStack = false;
			}
			_stack = new ArrayList<>();
			_nextId = 0;
			_cycles = new ArrayList<>();

			for (TypeEntry t : _types.values()) {
				findLowestImportId(t);
			}
		}
		
		int findLowestImportId(TypeEntry e) {
			e.id = _nextId++;
			e.lowestReachable = e.id;
			_stack.add(e);
			e.inStack = true;

			for (String i : e.imports) {
				TypeEntry o = _types.get(i);
				if (null != o) {
					if (o.id == Integer.MAX_VALUE) {
						int r = this.findLowestImportId(o);
						if (e.lowestReachable > r) {
							e.lowestReachable = r;
						}
					} else if (o.inStack) {
						e.lowestReachable = o.lowestReachable;
					}
				}
			}
			
			if (e.lowestReachable == e.id) {
				TypeEntry c;
				List<TypeEntry> thisCycle = new ArrayList<>();
				do {
					c = _stack.remove(_stack.size() - 1);
					c.inStack = false;
					thisCycle.add(c);
				} while (c != e);
				if (thisCycle.size() > 1) {
					this._cycles.add(thisCycle);
				}
			}
			
			return e.lowestReachable;
		}
		
		boolean _csv;
		//List<CompilationUnit> _files;
		Map<String, TypeEntry> _types;
		boolean _wildCardsFound;

		int _nextId; 
		List<TypeEntry>_stack;
		List<List<TypeEntry>>_cycles;
	}
	
	
	private static void handleFile(File file, List<IHandler> handlers, boolean hasNext) {
		try {
			CompilationUnit unit = JavaParser.parse(file);
			for (IHandler handler : handlers) {
				handler.handleFile(file.toString(), unit, hasNext);
			}			
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void printUsage() {
		System.out.println("Java Dependency Parser -- parse some java files and determine approximate dependencies");
		System.out.println("");
		System.out.println("run with java -jar java-dependencies.jar <options> (<file>|<dir>)+ ");
		System.out.println("");
		System.out.println("available <options>:");
		System.out.println("  --simple     -- print dependencies in a vaguely human readable form");
		System.out.println("  --json       -- print dependencies as JSON, for further processing");
		System.out.println("  --cycles     -- detect cycles in the dependency graph and print them");
		System.out.println("  --cyclescsv  -- detect cycles in the dependency graph and print them as csv");
		System.out.println("  --csvheader  -- print csv header for the cyclescsv format");
		System.out.println("  --project=.. -- give the project name to be used for the cyclescsv format");
		
		System.out.println("");
	}
	
	public static void main(String[] args) {
		
		List<IHandler> handlers = new ArrayList<IHandler>();
		
		//List<String> nonOptions = new ArrayList<>();
		List<File> files = new ArrayList<>();
		List<File> dirs = new ArrayList<>();
		
		String name = null;
		
		if (0 < args.length) {
			IHandler printer = null;
			
			for (String arg : args) {
				if (arg.startsWith("--")) {
					if ("--simple".equals(arg)) {
						printUsage();
						return;
					} else if ("--simple".equals(arg)) {
						printer = new PrinterSimple(System.out);
					} else if ("--json".equals(arg)) {
						printer = new PrinterJson(System.out);
					} else if ("--cycles".equals(arg)) {
						handlers.add(new CycleFinder(false));
					} else if ("--cyclescsv".equals(arg)) {
						handlers.add(new CycleFinder(true));
					} else if ("--csvheader".equals(arg)) {
						System.out.println("name,cycles,classes_in_cycles,largest_cycle,num_types,has_wildcards,cycle_json");
					} else if (arg.startsWith("--project=")) {
						name = arg.substring("--project=".length());
					} else {
						System.out.println("unrecognized option: "+arg);
						printUsage();
						return;
					}
				} else if (arg.startsWith("-")) {
					System.out.println("unrecognized option: "+arg);
					printUsage();
					return;
				} else {
					File file = new File(arg);
					if (file.exists()) {
						if (file.isDirectory()) {
							dirs.add(file);
						}
						if (file.isFile()) {
							files.add(file);
						}
					}
				}
			}
			
			if (null != printer) {
				handlers.add(printer);
			}


			if (!files.isEmpty()) {
				String p = name;
				if (null == p) {
					p = files.get(0).getName();
				}
				for (IHandler h : handlers) {
					h.start(p);
				}
				for (File file : files) {
					if (file.isDirectory()) {
					} else if (file.exists()) {
						handleFile(file, handlers, false);
					}
				}
				for (IHandler h : handlers) {
					h.end(p);
				}
			}

			for (File dir : dirs) {
				String p = name;
				if (null == p) {
					p = dir.getName();
				}
				for (IHandler h : handlers) {
					h.start(p);
				}
				FileWalker fw = new FileWalker(dir, fn -> fn.getName().endsWith(".java"));
				while (fw.hasNext()) {
					File ff = fw.next();
					handleFile(ff, handlers, fw.hasNext());
				}
				for (IHandler h : handlers) {
					h.end(p);
				}
			}
		}

	}

}
