package graphql_clj;

import clojure.lang.IObj;
import clojure.lang.IMeta;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import clojure.lang.Symbol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class Parser {
    private static final int TOKEN_EOF = -1;
    private static final int TOKEN_INTEGER = 1;
    private static final int TOKEN_FLOAT = 2;
    private static final int TOKEN_STRING = 3;
    private static final int TOKEN_IDENT = 4;
    private static final int TOKEN_ELLIPSIS = 5;

    private static final char BYTE_ORDER_MARK = '\ufeff';

    private static final int STATE_NEGATIVE = -1;
    private static final int STATE_ZERO = 0;
    private static final int STATE_INTEGER = 1;
    private static final int STATE_DOT = 2;
    private static final int STATE_E = 3;
    
    private static final Keyword ALIAS = Keyword.intern("alias");
    private static final Keyword ARGUMENT = Keyword.intern("argument");
    private static final Keyword ARGUMENTS = Keyword.intern("arguments");
    private static final Keyword ARGUMENT_DEFINITION = Keyword.intern("argument-definition");
    private static final Keyword BASIC_TYPE = Keyword.intern("basic-type");
    private static final Keyword BOOLEAN_VALUE = Keyword.intern("boolean-value");
    private static final Keyword CONSTANTS = Keyword.intern("constants");
    private static final Keyword DEFAULT_VALUE = Keyword.intern("default-value");
    private static final Keyword DIRECTIVE = Keyword.intern("directive");
    private static final Keyword DIRECTIVES = Keyword.intern("directives");
    private static final Keyword DIRECTIVE_DEFINITION = Keyword.intern("directive-definition");
    private static final Keyword DOC = Keyword.intern("doc");
    private static final Keyword END = Keyword.intern("end");
    private static final Keyword ENUM_VALUE = Keyword.intern("enum-value");
    private static final Keyword ENUM_CONSTANT = Keyword.intern("enum-constant");
    private static final Keyword ENUM_DEFINITION = Keyword.intern("enum-definition");
    private static final Keyword EXTEND_TYPE_DEFINITION = Keyword.intern("extend-type-definition");
    private static final Keyword FIELDS = Keyword.intern("fields");
    private static final Keyword FLOAT_VALUE = Keyword.intern("float-value");
    private static final Keyword FRAGMENT_DEFINITION = Keyword.intern("fragment-definition");
    private static final Keyword FRAGMENT_SPREAD = Keyword.intern("fragment-spread");
    private static final Keyword IMAGE = Keyword.intern("image");
    private static final Keyword IMPLEMENTS = Keyword.intern("implements");
    private static final Keyword INLINE_FRAGMENT = Keyword.intern("inline-fragment");
    private static final Keyword INNER_TYPE = Keyword.intern("inner-type");
    private static final Keyword INPUT_DEFINITION = Keyword.intern("input-definition");
    private static final Keyword INTERFACE_DEFINITION = Keyword.intern("interface-definition");
    private static final Keyword INT_VALUE = Keyword.intern("int-value");
    private static final Keyword LIST_TYPE = Keyword.intern("list-type");
    private static final Keyword LIST_VALUE = Keyword.intern("list-value");
    private static final Keyword MEMBERS = Keyword.intern("members");
    private static final Keyword MUTATION = Keyword.intern("mutation");
    private static final Keyword NAME = Keyword.intern("name");
    private static final Keyword NULL_VALUE = Keyword.intern("null-value");
    private static final Keyword OBJECT_FIELD = Keyword.intern("object-field");
    private static final Keyword OBJECT_VALUE = Keyword.intern("object-value");
    private static final Keyword ON = Keyword.intern("on");
    private static final Keyword QUERY = Keyword.intern("query");
    private static final Keyword QUERY_DEFINITION = Keyword.intern("query-definition");
    private static final Keyword REQUIRED = Keyword.intern("required");
    private static final Keyword SCALAR_DEFINITION = Keyword.intern("scalar-definition");
    private static final Keyword SCHEMA = Keyword.intern("schema");
    private static final Keyword SCHEMA_DEFINITION = Keyword.intern("schema-definition");
    private static final Keyword SELECTION_FIELD = Keyword.intern("selection-field");
    private static final Keyword SELECTION_SET = Keyword.intern("selection-set");
    private static final Keyword START = Keyword.intern("start");
    private static final Keyword STRING_VALUE = Keyword.intern("string-value");
    private static final Keyword SUBSCRIPTION = Keyword.intern("subscription");
    private static final Keyword TAG = Keyword.intern("tag");
    private static final Keyword TYPE = Keyword.intern("type");
    private static final Keyword TYPE_DEFINITION = Keyword.intern("type-definition");
    private static final Keyword TYPE_FIELD = Keyword.intern("type-field");
    private static final Keyword TYPE_SYSTEM_DEFINITIONS = Keyword.intern("type-system-definitions");
    private static final Keyword UNION_DEFINITION = Keyword.intern("union-definition");
    private static final Keyword VALUE = Keyword.intern("value");
    private static final Keyword VALUES = Keyword.intern("values");
    private static final Keyword VARIABLE_DEFINITION = Keyword.intern("variable-definition");
    private static final Keyword VARIABLE_DEFINITIONS = Keyword.intern("variable-definitions");
    private static final Keyword VARIABLE_REFERENCE = Keyword.intern("variable-reference");

    private final String _input;
    private int _limit;
    private int _index;
    private int _line;
    private int _lineStart;

    private int _token;
    private Location _startLocation;
    private String _image;
    private final StringBuilder _stringValue = new StringBuilder();
    private int _docStart;
    private int _docEnd;
    
    public Parser(String input) {
        _line = 1;
        _lineStart = -1;
        _input = input;
        _limit = input.length();
        // populate the first token
        next();
    }

    static PersistentArrayMap map(Object ... args) {
        return new PersistentArrayMap(args);
    }

    private static IObj node(IPersistentMap meta, Object ... kvpairs) {
        return new PersistentArrayMap(meta, kvpairs);
    }

    private static IObj nodeWithLoc(Object start, Object end, Object ... kvpairs) {
        return node(map(START, start, END, end), kvpairs);
    }


    Location location(int index) {
        return new Location(_line, index - _lineStart, index);
    }

    ParseException tokenError(int index, String msg) {
        throw new ParseException(location(index), msg);
    }

    static boolean isDigit(char ch) {
        return '0' <= ch && ch <= '9';
    }
    
    void next() {
        _token = nextImpl();
        // System.out.println("TOKEN: "+tokenDescription());
    }

    void tabAdjust(int tabIndex) {
        // TODO: update _lineStart to make subsequent column
        // computation correct.
    }

    String documentComment() {
        return _input.substring(_docStart, _docEnd);
    }

    private int nextImpl() {
        char ch;
        int tokenStart = _index;
        int i;
        int state;

        // clear out the document comment from the previous token.
        // use -2 to indicate we have not seen a new line.  We have a
        // special case for the first charater in the input, that is
        // assumed to follow a newline.
        _docStart = tokenStart == 0 ? -1 : -2;
        
    outer:
        for (;; tokenStart++) {
            if (tokenStart >= _limit) {
                _index = tokenStart;
                return TOKEN_EOF;
            }

            switch (ch = _input.charAt(tokenStart)) {
            case '\t':
                tabAdjust(tokenStart);
                // fall through
            case ' ':
            case ',':
            case BYTE_ORDER_MARK:
                continue;
            case '\r':
                if (tokenStart+1 < _limit && '\n' == _input.charAt(tokenStart+1))
                    tokenStart++;
                // fall through
            case '\n':
                _line++;
                _lineStart = tokenStart;
                // seen a newline, if there's a comment on the
                // following line, it can start a document comment.
                // This also clears out the previous document comment
                // if there was one, since we want our document
                // comments to be contiguous.
                _docStart = -1;
                continue;
            case '#':
                if (_docStart == -1)
                    _docStart = tokenStart;
            comment:
                while (++tokenStart < _limit) {
                    switch (_input.charAt(_docEnd = tokenStart)) {
                    case '\r':
                        if (tokenStart+1 < _limit && '\n' == _input.charAt(tokenStart+1))
                            tokenStart++;
                        // fall through
                    case '\n':
                        _line++;
                        _lineStart = tokenStart;
                        if (_docStart < 0) {
                            // this is not a document comment, but the
                            // next comment could be.
                            _docStart = -1;
                            continue outer;
                        }
                        
                        while (++tokenStart < _limit) {
                            switch (ch = _input.charAt(tokenStart)) {
                            case '\t':
                                tabAdjust(tokenStart);
                                // fall through
                            case ' ':
                            case ',':
                            case BYTE_ORDER_MARK:
                                continue;
                            case '#':
                                continue comment;
                            default:
                                // process the character again
                                --tokenStart;
                                continue outer;
                            }
                        }
                        continue outer;
                    }
                }
                _index = tokenStart;
                return TOKEN_EOF;

            case '@':
            case '{':
            case '(':
            case '[':
            case '$':
                // don't waste time setting the start location for
                // tokens that do not start productions.  these 5
                // tokens can start productions, the next 7 cannot.
                _startLocation = location(tokenStart);
                // fall through
            case ']':
            case '!':
            case ':':
            case '}':
            case ')':
            case '|':
            case '=':
                _index = tokenStart + 1;
                return ch;

            case '.':
                if (tokenStart + 2 < _limit
                    && '.' == _input.charAt(tokenStart + 1)
                    && '.' == _input.charAt(tokenStart + 2)) {
                    _startLocation = location(tokenStart);
                    _index = tokenStart + 3;
                    return TOKEN_ELLIPSIS;
                } else {
                    throw tokenError(
                        tokenStart,
                        String.format(
                            "invalid character sequence '%s', did you mean '...'?",
                            _input.substring(tokenStart, tokenStart+2)));
                }
                
            case '"':
                _startLocation = location(tokenStart);
                _stringValue.setLength(0);
                for (i=tokenStart+1 ; i<_limit ; ) {
                    if ('"' == (ch = _input.charAt(i++))) {
                        // TODO: the image should probably include the quotes
                        _image = _input.substring(tokenStart+1, (_index = i)-1);
                        return TOKEN_STRING;
                    } else if ('\\' == ch) {
                        if (i >= _limit)
                            throw tokenError(i, "unterminated string");
                        switch (ch = _input.charAt(i++)) {
                        case '\"': case '\\': case '/':
                            break;
                        case 'b': ch = '\b'; break;
                        case 'f': ch = '\f'; break;
                        case 'r': ch = '\r'; break;
                        case 't': ch = '\t'; break;
                        case 'n': ch = '\n'; break;

                        case 'u':
                            if (i+4 >= _limit)
                                throw tokenError(i, "unterminated string");
                            for (int n=i+4 ; i<n ; ) {
                                char x = _input.charAt(i++);
                                if ('0' <= x && x <= '9') {
                                    ch = (char)((ch << 4) | (x - '0'));
                                } else if ('A' <= (x & ~0x20) && x <= 'F') {
                                    // if x is a letter, then x & ~0x20 will convert
                                    // lowercase to uppercase, and leave uppercase alone
                                    ch = (char)((ch << 4) | (x - ('A' - 10)));
                                } else {
                                    throw tokenError(i, "invalid hex escape");
                                }
                            }
                            break;

                        default:
                            throw tokenError(i, "invalid escape sequence");
                        }
                    } else if (ch < ' ') {
                        if (ch == '\t') {
                            tabAdjust(i-1);
                        } else {
                            throw tokenError(i, "invalid character in string");
                        }
                    }
                    _stringValue.append(ch);
                }
                throw tokenError(_limit, "unterminated string");
            case '_':
            case 'A': case 'B': case 'C': case 'D': case 'E':
            case 'F': case 'G': case 'H': case 'I': case 'J':
            case 'K': case 'L': case 'M': case 'N': case 'O':
            case 'P': case 'Q': case 'R': case 'S': case 'T':
            case 'U': case 'V': case 'W': case 'X': case 'Y':
            case 'Z':
            case 'a': case 'b': case 'c': case 'd': case 'e':
            case 'f': case 'g': case 'h': case 'i': case 'j':
            case 'k': case 'l': case 'm': case 'n': case 'o':
            case 'p': case 'q': case 'r': case 's': case 't':
            case 'u': case 'v': case 'w': case 'x': case 'y':
            case 'z':
                _startLocation = location(tokenStart);
                for (i=tokenStart ; ++i<_limit ; ) {
                    ch = _input.charAt(i);
                    if (!('a' <= ch && ch <= 'z' ||
                          'A' <= ch && ch <= 'Z' ||
                          '0' <= ch && ch <= '9' || ch == '_'))
                        break;
                }
                _image = _input.substring(tokenStart, _index = i);
                return TOKEN_IDENT;

            case '-':
                state = STATE_NEGATIVE;
                break;
            case '0':
                state = STATE_ZERO;
                break;
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                state = STATE_INTEGER;
                break;
            default:
                throw tokenError(tokenStart, String.format("invalid character '%c'", ch));
            } // switch on char

            _startLocation = location(tokenStart);

        stateLoop:
            for (i=tokenStart+1 ;; ++i) {
                switch (state) {
                case STATE_ZERO:
                    if (i < _limit) {
                        ch = _input.charAt(i);
                        if ('.' == ch) {
                            state = STATE_DOT;
                            continue;
                        } else if ('e' == ch || 'E' == ch) {
                            state = STATE_E;
                            continue;
                        } else if ('0' <= ch && ch <= '9') {
                            throw tokenError(i, "zero-prefixed numbers are not allowed");
                        }
                    }
                    _image = _input.substring(tokenStart, _index = i);
                    return TOKEN_INTEGER;
                    
                case STATE_NEGATIVE:
                    if (i >= _limit)
                        throw tokenError(i, "expected digit after '-'");
                    
                    ch = _input.charAt(i);
                    if ('1' <= ch && ch <= '9') {
                        state = STATE_INTEGER;
                        // fall through
                    } else if ('0' == ch) {
                        state = STATE_ZERO;
                        continue stateLoop;
                    } else {
                        throw tokenError(i, "expected digit after '-'");
                    }

                case STATE_INTEGER:
                    for ( ; i < _limit ; ++i) {
                        ch = _input.charAt(i);
                        if ('0' <= ch && ch <= '9') {
                            continue;
                        } else if ('.' == ch) {
                            state = STATE_DOT;
                        } else if ('e' == ch || 'E' == ch) {
                            state = STATE_E;
                        } else {
                            break;
                        }
                        continue stateLoop;
                    }
                    _image = _input.substring(tokenStart, _index = i);
                    return TOKEN_INTEGER;
                case STATE_DOT:
                    if (!((i < _limit) && '0' <= (ch = _input.charAt(i)) && ch <= '9'))
                        throw tokenError(i, "expected digit after '.'");
                    while (++i < _limit) {
                        ch = _input.charAt(i);
                        if ('0' <= ch && ch <= '9')
                            continue;
                        if ('e' == ch || 'E' == ch) {
                            state = STATE_E;
                            continue stateLoop;
                        }
                        break;
                    }
                    _image = _input.substring(tokenStart, _index = i);
                    return TOKEN_FLOAT;
                            
                case STATE_E:
                    if (i >= _limit)
                        throw tokenError(i, "expected '+', '-', or digit after 'e'");
                    if ('+' == (ch = _input.charAt(i)) || '-' == ch) {
                        if (++i >= _limit)
                            throw tokenError(i, "expected digit after 'e'");
                        ch = _input.charAt(i);
                    }
                    if (!('0' <= ch && ch <= '9'))
                        throw tokenError(i, "expected digit after 'e'");
                    while (++i < _limit) {
                        ch = _input.charAt(i);
                        if (!('0' <= ch && ch <= '9'))
                            break;
                    }
                    _image = _input.substring(tokenStart, _index = i);
                    return TOKEN_FLOAT;

                default:
                    throw new AssertionError("bad state: "+state);
                }
            } // stateLoop
            
        } // outer: for
        
    }

    private String tokenDescription() {
        return tokenDescription(_token);
    }

    private String tokenDescription(int kind) {
        switch (kind) {
        case TOKEN_EOF:
            return "end of file";
        case TOKEN_INTEGER:
        case TOKEN_FLOAT:
        case TOKEN_STRING:
            return "'" + _image + "'";
        case TOKEN_IDENT:
            return _image;
        case TOKEN_ELLIPSIS:
            return "'...'";
        default:
            return new String(new char[] { '\'', (char)kind, '\'' });
        }
    }

    private void expect(int kind) {
        if (_token != kind)
            throw new ParseException(
                _startLocation,
                "Expected "+tokenDescription(kind)+
                ", found "+tokenDescription());
    }

    private void consume(int kind) {
        expect(kind);
        next();
    }

    private Symbol parseName() {
        if (TOKEN_IDENT != _token)
            throw new ParseException(
                _startLocation,
                "Expected name, found "+tokenDescription());
        
        Symbol name = (Symbol)Symbol.intern(_image)
            .withMeta(map(START, _startLocation, END, location(_index)));
        next();
        return name;
    }

    private IObj parseBasicType() {
        Symbol name = parseName();
        return node(name.meta(), TAG, BASIC_TYPE, NAME, name);
    }

    private IObj parseTypeRef() {
        if ('[' == _token) {
            IObj start = _startLocation;
            next();
            IObj inner = parseTypeRef();
            IObj end = location(_index);
            consume(']');
            if ('!' != _token) {
                return nodeWithLoc(
                    start, end,
                    TAG, LIST_TYPE,
                    INNER_TYPE, inner);
            } else {
                end = location(_index);
                next(); // '!'
                return nodeWithLoc(
                    start, end,
                    TAG, LIST_TYPE,
                    INNER_TYPE, inner,
                    REQUIRED, true);
            }
        } else {
            Symbol name = parseName();
            Object end = name.meta().valAt(END);
            if ('!' != _token) {
                return nodeWithLoc(
                    name.meta().valAt(START), end,
                    TAG, BASIC_TYPE,
                    NAME, name);
            } else {
                end = location(_index);
                next();
                return nodeWithLoc(
                    name.meta().valAt(START), end,
                    TAG, BASIC_TYPE,
                    NAME, name,
                    REQUIRED, true);
            }
        }
    }

    private IObj parseVec(int startToken, int endToken, Supplier<Object> itemParser) {
        Object start = _startLocation;
        consume(startToken);
        List<Object> list = new ArrayList();
        while (_token != endToken) {
            list.add(itemParser.get());
        }
        Object end = location(_index);
        next();
        return PersistentVector.create(list).withMeta(
            map(START, start, END, end));
    }

    IObj parseArgumentDefinition() {
        Object[] map = new Object[8];
        int i = 0;
        map[i++] = TAG;
        map[i++] = ARGUMENT_DEFINITION;
        Symbol name = parseName();
        map[i++] = NAME;
        map[i++] = name;
        consume(':');
        IObj type = parseTypeRef();
        map[i++] = TYPE;
        map[i++] = type;

        if ('=' == _token) {
            next();
            IObj defVal = parseValue();
            map[i++] = DEFAULT_VALUE;
            map[i++] = defVal;
        }

        return nodeWithLoc(
            name.meta().valAt(START),
            ((IObj)map[i-1]).meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    // package private to avoid compiler-generated private accesor
    // with lambda usage.
    IObj parseTypeField() {
        Object[] map = new Object[10];
        int i = 0;
        map[i++] = TAG;
        map[i++] = TYPE_FIELD;

        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }
        
        Symbol name = parseName();
        map[i++] = NAME;
        map[i++] = name;

        if ('(' == _token) {
            map[i++] = ARGUMENTS;
            map[i++] = parseVec('(', ')', this::parseArgumentDefinition).withMeta(null);
        }

        consume(':');

        IObj type = parseTypeRef();
        map[i++] = TYPE;
        map[i++] = type;
        
        return nodeWithLoc(
            name.meta().valAt(START),
            type.meta().valAt(END),
            Arrays.copyOf(map, i));
        
        // Object arguments = ('(' == _token)
        //     ? parseVec('(', ')', this::parseArgumentDefinition)
        //     : null;
        // consume(':');
        // IObj type = parseTypeRef();
        // return nodeWithLoc(
        //     name.meta().valAt(START),
        //     type.meta().valAt(END),
        //     TAG, TYPE_FIELD,
        //     NAME, name,
        //     ARGUMENTS, arguments, 
        //     TYPE, type);
    }

    IObj parseTypeDefinition(IObj start, Keyword tag) {
        Object[] map = new Object[10];
        int i = 0;
        map[i++] = TAG;
        map[i++] = tag;

        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }

        next(); // "type"
        Symbol name = parseName();
        map[i++] = NAME;
        map[i++] = name;
        
        if (TOKEN_IDENT == _token && "implements".equals(_image)) {
            next();
            List<IObj> impls = new ArrayList<>();
            do {
                impls.add(parseBasicType());
            } while (TOKEN_IDENT == _token);

            map[i++] = IMPLEMENTS;
            map[i++] = PersistentVector.create(impls);
        }

        IObj fields = parseVec('{', '}', this::parseTypeField);
        Object end = fields.meta().valAt(END);
        map[i++] = FIELDS;
        map[i++] = fields.withMeta(null);  // TODO: remove withMeta(null)

        return nodeWithLoc(
            start, end,
            Arrays.copyOf(map, i));
    }

    IObj parseInterfaceDefinition() {
        Object[] map = new Object[8];
        int i = 0;

        IObj start = _startLocation;

        map[i++] = TAG;
        map[i++] = INTERFACE_DEFINITION;
        
        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }

        next();

        map[i++] = NAME;
        map[i++] = parseName();
        IObj fields = parseVec('{', '}', this::parseTypeField);
        map[i++] = FIELDS;
        map[i++] = fields.withMeta(null);

        return nodeWithLoc(
            start, fields.meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    IObj parseInputDefinition() {
        Object[] map = new Object[8];
        int i = 0;
        IObj start = _startLocation;

        map[i++] = TAG;
        map[i++] = INPUT_DEFINITION;        
        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }

        next(); // "input"
        map[i++] = NAME;
        map[i++] = parseName();
        IObj fields = parseVec('{', '}', this::parseTypeField);
        map[i++] = FIELDS;
        map[i++] = fields.withMeta(null);
        
        return nodeWithLoc(
            start, fields.meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    IObj parseUnionDefinition() {
        Object[] map = new Object[8];
        int i = 0;
        IObj start = _startLocation;
        map[i++] = TAG;
        map[i++] = UNION_DEFINITION;
        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }
        next(); // "union"
        map[i++] = NAME;
        map[i++] = parseName();
        consume('=');
        List<Object> members = new ArrayList<>();
        IObj lastType = parseBasicType();
        members.add(lastType);
        while ('|' == _token) {
            next();
            members.add(lastType = parseBasicType());
        }
        map[i++] = MEMBERS;
        map[i++] = PersistentVector.create(members);
        return nodeWithLoc(
            start, lastType.meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    Keyword parseSchemaTag() {
        if (TOKEN_IDENT == _token) {
            switch (_image) {
            case "query":
                return QUERY;
            case "mutation":
                return MUTATION;
            case "subscription":
                return SUBSCRIPTION;
            }
        }
        throw new ParseException(
            _startLocation,
            "expected 'query', 'mutation', or 'subscription'.  Found "+
            tokenDescription());
    }

    IObj parseSchemaType() {
        IObj start = _startLocation;
        Keyword tag = parseSchemaTag();
        next(); // consume the tag, the parseSchemaType does not
        consume(':');
        Symbol name = parseName();
        return nodeWithLoc(
            start, name.meta().valAt(END),
            TAG, tag,
            NAME, name);
    }

    IObj parseSchemaDefinition() {
        IObj start = _startLocation;
        next(); // "schema"
        IObj members = parseVec('{', '}', this::parseSchemaType);
        return nodeWithLoc(
            start, members.meta().valAt(END),
            TAG, SCHEMA_DEFINITION,
            MEMBERS, members.withMeta(null)); // TODO: remove withMeta(null)
    }

    IObj parseEnumConstant() {
        Object[] map = new Object[8];
        int i = 0;
        map[i++] = TAG;
        map[i++] = ENUM_CONSTANT;
        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }
        Symbol name = parseName();
        map[i++] = NAME;
        map[i++] = name;
        IObj directives = parseDirectives();
        IPersistentMap meta = name.meta();
        if (directives != null) {
            map[i++] = DIRECTIVES;
            map[i++] = directives.withMeta(null);
            meta = map(START, name.meta().valAt(START),
                       END, directives.meta().valAt(END));
        }

        return node(meta, Arrays.copyOf(map, i));
    }

    IObj parseEnumDefinition() {
        Object[] map = new Object[8];
        int i = 0;
        IObj start = _startLocation;
        map[i++] = TAG;
        map[i++] = ENUM_DEFINITION;
        if (_docStart >= 0) {
            map[i++] = DOC;
            map[i++] = documentComment();
        }
        next(); // "enum"
        map[i++] = NAME;
        map[i++] = parseName();
        IObj constants = parseVec('{', '}', this::parseEnumConstant);
        map[i++] = CONSTANTS;
        map[i++] = constants.withMeta(null);

        return nodeWithLoc(
            start, constants.meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    IObj parseTypeConditionOpt() {
        if (TOKEN_IDENT != _token || !"on".equals(_image))
            return null;
        
        IObj start = _startLocation;
        next();
        IObj type = parseBasicType();

        // TODO: for backwards compatilbity with the AST, the
        // type-condition's "on" is the start location.
        return type.withMeta(type.meta().assoc(START, start));
    }

    IObj parseDirectiveDefinition() {
        Object[] map = new Object[6];
        int i = 0;
        IObj start = _startLocation;
        next(); // "directive"
        expect('@');
        map[i++] = TAG;
        map[i++] = DIRECTIVE_DEFINITION;

        Symbol name = parseName();
        map[i++] = NAME;
        map[i++] = name;
        
        IObj on = parseTypeConditionOpt();
        if (on != null) {
            map[i++] = ON;
            map[i++] = on;
        }

        return nodeWithLoc(
            start, (on == null ? name : on).meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    IObj parseExtendTypeDefinition() {
        IObj start = _startLocation;
        next(); // "extend"
        return parseTypeDefinition(start, EXTEND_TYPE_DEFINITION);
    }

    IObj parseScalarDefinition() {
        IObj start = _startLocation;
        next(); // "scalar"
        Symbol name = parseName();
        return nodeWithLoc(
            start, name.meta().valAt(END),
            TAG, SCALAR_DEFINITION,
            NAME, name);
    }

    IObj parseTypeSystemDefinition() {
        if (TOKEN_IDENT == _token) {
            switch (_image) {
            case "type":
                return parseTypeDefinition(_startLocation, TYPE_DEFINITION);
            case "interface":
                return parseInterfaceDefinition();
            case "union":
                return parseUnionDefinition();
            case "schema":
                return parseSchemaDefinition();
            case "enum":
                return parseEnumDefinition();
            case "input":
                return parseInputDefinition();
            case "directive":
                return parseDirectiveDefinition();
            case "extend":
                return parseExtendTypeDefinition();
            case "scalar":
                return parseScalarDefinition();
            }
        }

        throw new ParseException(
            _startLocation,
            "Expected 'type', 'interface', 'union', 'schema', 'enum', 'input', "+
            "'directive', 'extend', or 'scalar'.  Found "+tokenDescription());
    }

    private PersistentVector parseTypeSystemDefinitions() {
        if (_token == TOKEN_EOF){
            return PersistentVector.EMPTY;
        }
        List<IObj> defs = new ArrayList<>();
        do {
            defs.add(parseTypeSystemDefinition());
        } while (_token != TOKEN_EOF);
        
        return PersistentVector.create(defs);
    }

    public IObj parseSchema() {
        PersistentVector tsd = parseTypeSystemDefinitions();
        Object start;
        Object end;
        return map(
            TAG, SCHEMA,
            TYPE_SYSTEM_DEFINITIONS, tsd);
    }

    private IObj parseSelection() {
        if (TOKEN_IDENT == _token) {
            int i = 0;
            Object[] map = new Object[12];
            map[i++] = TAG;
            map[i++] = SELECTION_FIELD;
            
            Symbol name = parseName();
            Object start = name.meta().valAt(START);
            Symbol alias = null;
            if (':' == _token) {
                next();
                alias = name;
                name = parseName();
                map[i++] = ALIAS;
                map[i++] = alias;
            }
            map[i++] = NAME;
            map[i++] = name;

            Object end = name.meta().valAt(END);

            IObj arguments = null;
            if ('(' == _token) {
                arguments = parseVec('(', ')', this::parseArgument);
                end = arguments.meta().valAt(END);
                map[i++] = ARGUMENTS;
                map[i++] = arguments.withMeta(null);
            }

            IObj directives = parseDirectives();
            if (directives != null) {
                end = directives.meta().valAt(END);
                map[i++] = DIRECTIVES;
                map[i++] = directives;
            }

            IObj sset = null;
            if ('{' == _token) {
                sset = parseSelectionSet();
                end = sset.meta().valAt(END);
                map[i++] = SELECTION_SET;
                map[i++] = sset.withMeta(null);
            }
            return nodeWithLoc(start, end, Arrays.copyOf(map, i));
            // return nodeWithLoc(
            //     start, end,
            //     TAG, SELECTION_FIELD,
            //     NAME, name,
            //     ALIAS, alias,
            //     ARGUMENTS, arguments,
            //     DIRECTIVES, directives,
            //     SELECTION_SET, sset);
        } else if (TOKEN_ELLIPSIS == _token) {
            int i = 0;
            Object[] map = new Object[8];
            map[i++] = TAG;
            i++; // added later
            
            IObj start = _startLocation;
            next();
            IObj on = null;
            if (TOKEN_IDENT == _token) {
                if ("on".equals(_image)) {
                    // TODO: convert this if block to parseTypeCondition
                    // note: that this is the correct way to populate `on`
                    on = parseTypeConditionOpt();
                    map[i++] = ON;
                    map[i++] = on;
                } else {
                    Symbol name = parseName();
                    map[i++] = NAME;
                    map[i++] = name;
                    
                    IObj directives = parseDirectives();
                    if (directives != null) {
                        map[i++] = DIRECTIVES;
                        map[i++] = directives;
                    }
                    map[1] = FRAGMENT_SPREAD;
                    
                    return nodeWithLoc(
                        start, (directives != null ? directives : name).meta().valAt(END),
                        Arrays.copyOf(map, i));
                        // TAG, FRAGMENT_SPREAD,
                        // NAME, name,
                        // DIRECTIVES, directives);
                }
            }

            map[1] = INLINE_FRAGMENT;
            
            IObj directives = parseDirectives();
            if (directives != null) {
                map[i++] = DIRECTIVES;
                map[i++] = directives;
            }
            
            IObj sset = parseSelectionSet();
            map[i++] = SELECTION_SET;
            map[i++] = sset.withMeta(null);

            return nodeWithLoc(start, sset.meta().valAt(END), Arrays.copyOf(map, i));
            // return nodeWithLoc(
            //     start, sset.meta().valAt(END),
            //     TAG, INLINE_FRAGMENT,
            //     ON, on,
            //     SELECTION_SET, sset);
        } else {
            throw new ParseException(
                _startLocation,
                "Expected field name or '...'.  Found "+tokenDescription());
        }
    }

    private IObj parseSelectionSet() {
        return parseVec('{', '}', this::parseSelection);
    }

    private IObj parseAnonymousQuery() {
        IObj sset = parseSelectionSet();
        return node(
            sset.meta(),
            TAG, SELECTION_SET,
            SELECTION_SET, sset.withMeta(null)); // TODO: remove withMeta(null)
    }

    private IObj parseObjectField() {
        Symbol name = parseName();
        consume(':');
        IObj value = parseValue();
        return nodeWithLoc(
            name.meta().valAt(START),
            value.meta().valAt(END),
            TAG, OBJECT_FIELD,
            NAME, name,
            VALUE, value);
    }

    private IObj parseVarRef() {
        IObj start = _startLocation;
        next(); // '$'
        Symbol name = parseName();
        return nodeWithLoc(
            start, name.meta().valAt(END),
            TAG, VARIABLE_REFERENCE,
            NAME, name);
    }

    private IObj parseObjectValue() {
        IObj fields = parseVec('{', '}', this::parseObjectField);
        return node(
            fields.meta(),
            TAG, OBJECT_VALUE,
            FIELDS, fields.withMeta(null)); // TODO: remove withMeta(null)
    }

    private IObj parseListValue() {
        IObj values = parseVec('[', ']', this::parseValue);
        return node(
            values.meta(),
            TAG, LIST_VALUE,
            VALUES, values.withMeta(null)); // TODO: remove withMeta(null)
    }
    
    private IObj parseValue() {
        Keyword tag;
        Object value;
        
        switch (_token) {
        case TOKEN_INTEGER:
            tag = INT_VALUE;
            value = Long.parseLong(_image, 10);
            break;
        case TOKEN_FLOAT:
            tag = FLOAT_VALUE;
            value = new Double(_image);
            break;
        case TOKEN_STRING:
            tag = STRING_VALUE;
            value = _stringValue.toString();
            break;
        case TOKEN_IDENT:
            switch (_image) {
            case "null":
                tag = NULL_VALUE;
                value = null;
                break;
            case "true":
                tag = BOOLEAN_VALUE;
                value = Boolean.TRUE;
                break;
            case "false":
                tag = BOOLEAN_VALUE;
                value = Boolean.FALSE;
                break;
            default:
                tag = ENUM_VALUE;
                value = Symbol.intern(_image);
                break;
            }
            break;
        case '$':
            return parseVarRef();
        case '[':
            return parseListValue();
        case '{':
            return parseObjectValue();
        default:
            throw new ParseException(
                _startLocation,
                "expected value, found "+tokenDescription());
        }

        IObj start = _startLocation;
        IObj end = location(_index);
        String image = _image;
        next();
        return nodeWithLoc(
            start, end,
            TAG, tag,
            IMAGE, image,
            VALUE, value);
    }

    private IObj parseVariableDefinition() {
        IObj start = _startLocation;
        consume('$');
        Symbol name = parseName();
        consume(':');
        IObj type = parseTypeRef();

        if ('=' != _token) {
            return nodeWithLoc(
                start, type.meta().valAt(END),
                TAG, VARIABLE_DEFINITION,
                NAME, name,
                TYPE, type);
        } else {
            next();
            IObj defVal = parseValue();
            return nodeWithLoc(
                start, defVal.meta().valAt(END),
                TAG, VARIABLE_DEFINITION,
                NAME, name,
                TYPE, type,
                DEFAULT_VALUE, defVal);
        }
    }

    private IObj parseArgument() {
        Symbol name = parseName();
        consume(':');
        IObj value = parseValue();
        return nodeWithLoc(
            name.meta().valAt(START),
            value.meta().valAt(END),
            TAG, ARGUMENT,
            NAME, name,
            VALUE, value);
    }

    private IObj parseDirectives() {
        if ('@' != _token)
            return null;

        IObj firstStart = _startLocation;
        Object lastEnd;
        List<Object> directives = new ArrayList<>();
        Object[] map = new Object[6];
        map[0] = TAG;
        map[1] = DIRECTIVE;
        do {
            IObj start = _startLocation;
            next(); // '@'
            Symbol name = parseName();
            map[2] = NAME;
            map[3] = name;

            int i = 4;
            
            Object end;
            IObj arguments = null;
            if ('(' == _token) {
                arguments = parseVec('(', ')', this::parseArgument);
                end = arguments.meta().valAt(END);
                map[i++] = ARGUMENTS;
                map[i++] = arguments.withMeta(null); // TODO: remove withMeta(null)
            } else {
                end = name.meta().valAt(END);
            }

            directives.add(
                nodeWithLoc(
                    start, lastEnd = end,
                    Arrays.copyOf(map, i)));
        } while ('@' == _token);

        return PersistentVector.create(directives)
            .withMeta(map(START, firstStart, END, lastEnd));
    }

    private IObj parseOperationDefinition(Keyword tag) {
        Object[] map = new Object[10];
        int i = 0;
        map[i++] = TAG;
        map[i++] = tag;
        
        IObj start = _startLocation;
        next(); // "query" or "mutation"
        Symbol name = null;
        if (TOKEN_IDENT == _token) {
            name = parseName();
            map[i++] = NAME;
            map[i++] = name;
        }
        boolean metaBug = false;
        // optional
        IObj varDefs = null;
        if ('(' == _token) {
            varDefs = parseVec('(', ')', this::parseVariableDefinition);
            map[i++] = VARIABLE_DEFINITIONS;
            map[i++] = varDefs.withMeta(null); // TODO: remove withMeta(null)
            metaBug = true;
        }
        
        IObj directives = parseDirectives();
        if (directives != null) {
            map[i++] = DIRECTIVES;
            map[i++] = directives;
            metaBug = true;
        }
        
        IObj sset = parseSelectionSet();
        map[i++] = SELECTION_SET;
        map[i++] = sset.withMeta(null); // TODO: remove withMeta(null)
    
        return nodeWithLoc(
            start, sset.meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    private IObj parseFragmentDefinition() {
        Object[] map = new Object[10];
        int i = 0;
        map[i++] = TAG;
        map[i++] = FRAGMENT_DEFINITION;
        
        IObj start = _startLocation;
        next(); // "fragment"
        Symbol name = parseName();
        map[i++] = NAME;
        map[i++] = name;
        
        IObj on = parseTypeConditionOpt();
        if (on != null) {
            map[i++] = ON;
            map[i++] = on;
        }
        
        IObj directives = parseDirectives();
        if (directives != null) {
            map[i++] = DIRECTIVES;
            map[i++] = directives;
        }
        
        IObj sset = parseSelectionSet();
        map[i++] = SELECTION_SET;
        map[i++] = sset.withMeta(null);
        return nodeWithLoc(
            start, sset.meta().valAt(END),
            Arrays.copyOf(map, i));
    }

    private IObj parseQueryElement() {
        if ('{' == _token) {
            return parseAnonymousQuery();
        } else if (TOKEN_IDENT == _token) {
            switch (_image) {
            case "query":
                return parseOperationDefinition(QUERY_DEFINITION);
            case "mutation":
                return parseOperationDefinition(MUTATION);
            case "fragment":
                return parseFragmentDefinition();
            }
        }
        throw new ParseException(
            _startLocation,
            "Expected '{', 'query', 'mutation', or 'fragment'.  Found "+tokenDescription());
    }

    public IObj parseQueryDocument() {
        if (_token == TOKEN_EOF) {
            return PersistentVector.EMPTY.withMeta(
                map(START, _startLocation, END, location(_index)));
        }
        
        List<IObj> queries = new ArrayList<>();
        do {
            queries.add(parseQueryElement());
        } while (_token != TOKEN_EOF);
        
        return PersistentVector.create(queries).withMeta(
            map(START, new Location(1, 1, 0),
                END, location(_index)));
    }
}