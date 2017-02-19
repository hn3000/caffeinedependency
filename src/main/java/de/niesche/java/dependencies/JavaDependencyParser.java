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
		void start();
		void handleFile(String fn, CompilationUnit cu, boolean hasNext);
		void end();
	}
	
	private static class PrinterSimple implements IHandler {
		PrinterSimple(PrintStream out) {
			_out = out;
		}
		@Override
		public void start() { }
		@Override
		public void end() { }
		
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
		public void start() {
			_out.println("{ \"files\": [");
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
		public void end() {
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
		public CycleFinder() {
			//_files = new ArrayList<>();
			_types = new HashMap<>();
			_wildCardsFound = false;
		}
		
		@Override
		public void start() {
			
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
		public void end() {
			findCycles();
			int maxCycle = _cycles.stream().mapToInt(List::size).reduce(0, Integer::max);
			int numInCycle = _cycles.stream().mapToInt(List::size).reduce(0, Integer::sum);
			
			System.out.println("found "+numInCycle+" types in "+_cycles.size()+" strongly connected components (largest is "+maxCycle+") after looking at "+_types.size()+" types");
			
			for (List<TypeEntry> list : _cycles) {
				System.out.println("[");
				for (TypeEntry typeEntry : list) {
					System.out.println("  "+typeEntry.name);
				}
				System.out.println("]");
			}
			
			if (_wildCardsFound) {
				System.out.println("Note: Found wildcard imports, result is a lower bound.");
			}
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
	
	
	public static void main(String[] args) {
		
		List<IHandler> handlers = new ArrayList<IHandler>();
		
		List<String> nonOptions = new ArrayList<>();
		
		if (0 < args.length) {
			IHandler printer = null;
			
			for (String arg : args) {
				if (arg.startsWith("--")) {
					if ("--simple".equals(arg)) {
						printer = new PrinterSimple(System.out);
					} else if ("--json".equals(arg)) {
						printer = new PrinterJson(System.out);
					} else if ("--cycles".equals(arg)) {
						handlers.add(new CycleFinder());
					}
				} else {
					nonOptions.add(arg);
				}
			}
			
			if (null != printer) {
				handlers.add(printer);
			}

			for (IHandler h : handlers) {
				h.start();
			}
			
			for (String arg : nonOptions) {
				File file = new File(arg);
				if (file.isDirectory()) {
					FileWalker fw = new FileWalker(file, fn -> fn.getName().endsWith(".java"));
					while (fw.hasNext()) {
						//System.out.println(fw.next());
						File ff = fw.next();
						handleFile(ff, handlers, fw.hasNext());
					}
				} else if (file.exists()) {
					handleFile(file, handlers, false);
				}
			}
			for (IHandler h : handlers) {
				h.end();
			}			
		}

	}

}
