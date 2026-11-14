package ipl.sable.render.clip;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes any vertex-processing shader honour the clip planes of {@link IplClipPlanes}.
 *
 * <p>The cut is written in CLIP SPACE, from the shader's own final {@code gl_Position}:
 * {@code gl_ClipDistance[i] = dot(gl_Position, ipl_ClipPlanes[i])}. Clip space is the one space
 * every world renderer agrees on (vanilla terrain, entities, block entities and text, Sodium,
 * Iris, Flywheel instancing, Veil, Sable sub-levels drawn through any model-view chain), so the
 * injection needs no knowledge of a shader's attributes, matrices or coordinate conventions and
 * no per-shader list. {@link IplClipPlanes} supplies the planes already transformed by the
 * inverse-transposed view-projection of the level being rendered.
 *
 * <p>Vertex and tessellation-evaluation shaders keep their {@code main} untouched apart from the
 * writes added at its end and before each of its {@code return}s: other preprocessors (Veil's
 * dynamic buffers, shader modifiers) may still inject code that uses main's locals. Geometry
 * shaders write the distances before every {@code EmitVertex()}. The rewrite is idempotent.
 *
 * <p>Screen-space programs opt out by containing {@value #OPT_OUT} (see IP's blit shaders);
 * GLSL ES and GLSL older than 1.30 have no {@code gl_ClipDistance} and are left unchanged.
 */
public final class IplClipShaderInjector {

	public enum Stage { VERTEX, TESSELLATION_EVALUATION, GEOMETRY }

	/** Opt-out marker for programs that draw in screen space. */
	public static final String OPT_OUT = "ipl:no-clip";

	private static final String WRITE = "ipl_clipWrite";
	private static final String EMIT = "ipl_clipEmitVertex";
	private static final String GUARD = "IPL_CLIP_DECLARED";

	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
	private static final Pattern EMIT_VERTEX = Pattern.compile("\\bEmitVertex\\s*\\(\\s*\\)");
	private static final Pattern VERSION =
		Pattern.compile("^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)(?:[ \\t]+(\\w+))?", Pattern.MULTILINE);
	private static final Pattern HEADER_DIRECTIVE =
		Pattern.compile("^[ \\t]*#[ \\t]*(?:version|extension)\\b[^\\n]*\\n?", Pattern.MULTILINE);
	private static final Pattern SIZED_DECLARATION =
		Pattern.compile("(\\bfloat\\s+gl_ClipDistance\\s*\\[\\s*)(\\d+)(\\s*\\])");
	private static final Pattern OUT_PER_VERTEX = Pattern.compile("\\bout\\s+gl_PerVertex\\s*\\{([^}]*)\\}");

	private IplClipShaderInjector() {}

	/** {@code source} with the clip-plane writes, or unchanged when not applicable. */
	public static String inject(Stage stage, String source) {
		if (source == null || source.contains(IplClipPlanes.UNIFORM) || source.contains(OPT_OUT)) return source;
		if (!hasClipDistance(source)) return source;
		String sized = widenDeclarations(source);
		return switch (stage) {
			case VERTEX, TESSELLATION_EVALUATION -> writeAtMainExits(sized);
			case GEOMETRY -> writeBeforeEmits(sized);
		};
	}

	private static boolean hasClipDistance(String source) {
		Matcher m = VERSION.matcher(source);
		// No directive in the text handed to us: the pipeline adds its own modern header.
		if (!m.find()) return true;
		int version = Integer.parseInt(m.group(1));
		return version >= 130 && !"es".equals(m.group(2));
	}

	/** Grows explicit {@code gl_ClipDistance} declarations to {@link IplClipPlanes#SLOTS}. */
	private static String widenDeclarations(String source) {
		Matcher sized = SIZED_DECLARATION.matcher(source);
		StringBuilder out = new StringBuilder();
		while (sized.find()) {
			int size = Integer.parseInt(sized.group(2));
			String replacement = size >= IplClipPlanes.SLOTS ? sized.group()
				: sized.group(1) + IplClipPlanes.SLOTS + sized.group(3);
			sized.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		sized.appendTail(out);
		String result = out.toString();
		Matcher block = OUT_PER_VERTEX.matcher(result);
		if (!block.find() || block.group(1).contains("gl_ClipDistance")) return result;
		int close = block.end() - 1;
		return result.substring(0, close) + "    float gl_ClipDistance[" + IplClipPlanes.SLOTS + "];\n"
			+ result.substring(close);
	}

	// ------------------------------------------------------------------ vertex / tess. evaluation

	/**
	 * Calls the writer at the end of every {@code main} body and before each of its returns. The
	 * writer is declared right before each main (guarded when there are several, e.g. one per
	 * preprocessor branch), so it is defined in whichever branch is compiled.
	 */
	private static String writeAtMainExits(String source) {
		List<int[]> mains = new ArrayList<>();
		Matcher m = MAIN.matcher(source);
		while (m.find()) {
			if (isInComment(source, m.start())) continue;
			int open = nextCodeChar(source, m.end());
			if (open < 0 || source.charAt(open) != '{') continue;
			int close = matchingBrace(source, open);
			if (close < 0) return source;
			mains.add(new int[]{m.start(), open, close});
		}
		if (mains.isEmpty()) return source;
		String declaration = mains.size() == 1 ? declaration(writer())
			: "#ifndef " + GUARD + "\n#define " + GUARD + "\n" + declaration(writer()) + "#endif\n";
		StringBuilder out = new StringBuilder(source);
		for (int i = mains.size() - 1; i >= 0; i--) {
			int[] main = mains.get(i);
			String body = source.substring(main[1] + 1, main[2]);
			out.replace(main[2], main[2], "\n    " + WRITE + "();\n");
			out.replace(main[1] + 1, main[2], returnsWithWrites(body));
			out.insert(main[0], declaration);
		}
		return out.toString();
	}

	/** {@code return;} inside main → {@code { ipl_clipWrite(); return; }} (comments skipped). */
	private static String returnsWithWrites(String body) {
		StringBuilder out = new StringBuilder(body.length() + 32);
		int i = 0;
		while (i < body.length()) {
			int skip = commentEnd(body, i);
			if (skip > i) {
				out.append(body, i, skip);
				i = skip;
				continue;
			}
			if (body.startsWith("return", i) && isWordStart(body, i) && isWordEnd(body, i + 6)) {
				int semicolon = nextCodeChar(body, i + 6);
				if (semicolon >= 0 && body.charAt(semicolon) == ';') {
					out.append("{ ").append(WRITE).append("(); return; }");
					i = semicolon + 1;
					continue;
				}
			}
			out.append(body.charAt(i++));
		}
		return out.toString();
	}

	private static String writer() {
		return "void " + WRITE + "() {\n" + writes("    ") + "}\n";
	}

	// ------------------------------------------------------------------ geometry

	private static String writeBeforeEmits(String source) {
		Matcher emits = EMIT_VERTEX.matcher(source);
		if (!emits.find()) return source;
		String rewired = emits.replaceAll(EMIT + "()");
		String helper = declaration("void " + EMIT + "() {\n" + writes("    ") + "    EmitVertex();\n}\n");
		int at = headerEnd(rewired);
		return rewired.substring(0, at) + helper + rewired.substring(at);
	}

	/** After the last {@code #version}/{@code #extension} line: declarations may follow them only. */
	private static int headerEnd(String source) {
		Matcher m = HEADER_DIRECTIVE.matcher(source);
		int end = 0;
		while (m.find()) end = m.end();
		return end;
	}

	// ------------------------------------------------------------------ shared text

	private static String declaration(String function) {
		return "uniform vec4 " + IplClipPlanes.UNIFORM + "[" + IplClipPlanes.SLOTS + "] = vec4["
			+ IplClipPlanes.SLOTS + "](" + neutralList() + ");\n" + function;
	}

	private static String writes(String indent) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < IplClipPlanes.SLOTS; i++) {
			sb.append(indent).append("gl_ClipDistance[").append(i).append("] = dot(gl_Position, ")
				.append(IplClipPlanes.UNIFORM).append('[').append(i).append("]);\n");
		}
		return sb.toString();
	}

	private static String neutralList() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < IplClipPlanes.SLOTS; i++) {
			if (i > 0) sb.append(", ");
			sb.append("vec4(0.0, 0.0, 0.0, 1.0)");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ lexing helpers

	/** End index of a comment starting at {@code i}, or {@code i} when none starts there. */
	private static int commentEnd(String s, int i) {
		if (s.startsWith("//", i)) {
			int nl = s.indexOf('\n', i);
			return nl < 0 ? s.length() : nl;
		}
		if (s.startsWith("/*", i)) {
			int end = s.indexOf("*/", i + 2);
			return end < 0 ? s.length() : end + 2;
		}
		return i;
	}

	private static int nextCodeChar(String s, int from) {
		int i = from;
		while (i < s.length()) {
			int skip = commentEnd(s, i);
			if (skip > i) { i = skip; continue; }
			if (!Character.isWhitespace(s.charAt(i))) return i;
			i++;
		}
		return -1;
	}

	private static int matchingBrace(String s, int open) {
		int depth = 0;
		int i = open;
		while (i < s.length()) {
			int skip = commentEnd(s, i);
			if (skip > i) { i = skip; continue; }
			char c = s.charAt(i);
			if (c == '{') depth++;
			else if (c == '}' && --depth == 0) return i;
			i++;
		}
		return -1;
	}

	private static boolean isInComment(String s, int at) {
		int i = 0;
		while (i < at) {
			int skip = commentEnd(s, i);
			if (skip > i) {
				if (skip > at) return true;
				i = skip;
				continue;
			}
			i++;
		}
		return false;
	}

	private static boolean isWordStart(String s, int i) {
		return i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1));
	}

	private static boolean isWordEnd(String s, int i) {
		return i >= s.length() || !Character.isJavaIdentifierPart(s.charAt(i));
	}
}
