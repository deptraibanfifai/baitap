#include <windows.h>
#include <GL/glut.h>
#include <iostream>
#include <vector>
#include <cmath>

using namespace std;

// Cửa sổ xén hình (Clipping Window)
const int x_min = -150, y_min = -100;
const int x_max = 150, y_max = 100;

// Mã vùng (Region Codes)
const int INSIDE = 0; // 0000
const int LEFT = 1;   // 0001
const int RIGHT = 2;  // 0010
const int BOTTOM = 4; // 0100
const int TOP = 8;    // 1000

struct Point {
    double x, y;
};

struct Line {
    Point p1, p2;
    bool isClipped;
};

vector<Point> mouseClicks;
vector<Line> lines;

const int LOGIC_WIDTH = 800;
const int LOGIC_HEIGHT = 600;

void init() {
    glClearColor(0.1f, 0.1f, 0.15f, 1.0f);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    gluOrtho2D(-LOGIC_WIDTH / 2, LOGIC_WIDTH / 2, -LOGIC_HEIGHT / 2, LOGIC_HEIGHT / 2);
}

void drawClippingWindow() {
    // Khung xén màu đỏ
    glColor3f(1.0f, 0.3f, 0.3f);
    glLineWidth(2.0f);
    glBegin(GL_LINE_LOOP);
        glVertex2i(x_min, y_min);
        glVertex2i(x_max, y_min);
        glVertex2i(x_max, y_max);
        glVertex2i(x_min, y_max);
    glEnd();

    // Hệ trục Oxy
    glColor3f(0.3f, 0.3f, 0.4f);
    glLineWidth(1.0f);
    glBegin(GL_LINES);
        glVertex2i(-400, 0); glVertex2i(400, 0);
        glVertex2i(0, -300); glVertex2i(0, 300);
    glEnd();
}

int computeCode(double x, double y) {
    int code = INSIDE;
    if (x < x_min)      code |= LEFT;
    else if (x > x_max) code |= RIGHT;
    if (y < y_min)      code |= BOTTOM;
    else if (y > y_max) code |= TOP;
    return code;
}

// -------------------------------------------------------------
// COHEN-SUTHERLAND chuẩn: Giữ phần TRONG, bỏ phần NGOÀI
// -------------------------------------------------------------
bool cohenSutherlandClip(Point p1, Point p2, Point &p1_out, Point &p2_out) {
    double x1 = p1.x, y1 = p1.y;
    double x2 = p2.x, y2 = p2.y;

    int code1 = computeCode(x1, y1);
    int code2 = computeCode(x2, y2);
    bool accept = false;

    while (true) {
        if ((code1 == 0) && (code2 == 0)) {
            // Cả 2 điểm đều nằm trong -> Chấp nhận giữ lại
            accept = true;
            break;
        } else if (code1 & code2) {
            // Cả 2 điểm cùng nằm ngoài 1 vùng -> Bỏ hẳn
            break; 
        } else {
            // Một đoạn nằm trong, một đoạn nằm ngoài -> Tiến hành xén
            int code_out = code1 ? code1 : code2;
            double x = 0, y = 0;

            if (code_out & TOP) {
                x = x1 + (x2 - x1) * (y_max - y1) / (y2 - y1);
                y = y_max;
            } else if (code_out & BOTTOM) {
                x = x1 + (x2 - x1) * (y_min - y1) / (y2 - y1);
                y = y_min;
            } else if (code_out & RIGHT) {
                y = y1 + (y2 - y1) * (x_max - x1) / (x2 - x1);
                x = x_max;
            } else if (code_out & LEFT) {
                y = y1 + (y2 - y1) * (x_min - x1) / (x2 - x1);
                x = x_min;
            }

            if (code_out == code1) {
                x1 = x; y1 = y;
                code1 = computeCode(x1, y1);
            } else {
                x2 = x; y2 = y;
                code2 = computeCode(x2, y2);
            }
        }
    }

    if (accept) {
        p1_out = {x1, y1};
        p2_out = {x2, y2};
        return true;
    }
    return false;
}

// -------------------------------------------------------------
// CHIA NHỊ PHÂN chuẩn
// -------------------------------------------------------------
Point findIntersection(Point pIn, Point pOut) {
    Point low = pIn, high = pOut, mid;
    for (int i = 0; i < 30; i++) {
        mid.x = (low.x + high.x) / 2.0;
        mid.y = (low.y + high.y) / 2.0;

        if (computeCode(mid.x, mid.y) == INSIDE) low = mid;
        else high = mid;
    }
    return low;
}

bool midpointSubdivisionClip(Point p1, Point p2, Point &p1_out, Point &p2_out) {
    int code1 = computeCode(p1.x, p1.y);
    int code2 = computeCode(p2.x, p2.y);

    if ((code1 & code2) != 0) return false;

    if (code1 == INSIDE && code2 == INSIDE) {
        p1_out = p1; p2_out = p2;
        return true;
    }

    if (code1 != INSIDE && code2 == INSIDE) {
        p1_out = findIntersection(p2, p1);
        p2_out = p2;
        return true;
    }
    
    if (code1 == INSIDE && code2 != INSIDE) {
        p1_out = p1;
        p2_out = findIntersection(p1, p2);
        return true;
    }

    if (code1 != INSIDE && code2 != INSIDE) {
        Point low = p1, high = p2, mid;
        bool foundInside = false;

        for (int i = 0; i < 30; i++) {
            mid.x = (low.x + high.x) / 2.0;
            mid.y = (low.y + high.y) / 2.0;
            if (computeCode(mid.x, mid.y) == INSIDE) {
                foundInside = true;
                break;
            }
            if ((computeCode(low.x, low.y) & computeCode(mid.x, mid.y)) == 0) high = mid;
            else low = mid;
        }

        if (foundInside) {
            p1_out = findIntersection(mid, p1);
            p2_out = findIntersection(mid, p2);
            return true;
        }
    }
    return false;
}

void display() {
    glClear(GL_COLOR_BUFFER_BIT);
    drawClippingWindow();

    for (const auto& line : lines) {
        if (line.isClipped) {
            glColor3f(0.0f, 1.0f, 0.8f); // Màu xanh ngọc rực rỡ cho đoạn được GIỮ LẠI
            glLineWidth(3.0f);
        } else {
            glColor3f(0.6f, 0.6f, 0.6f); // Đoạn chưa xén
            glLineWidth(1.5f);
        }

        glBegin(GL_LINES);
            glVertex2d(line.p1.x, line.p1.y);
            glVertex2d(line.p2.x, line.p2.y);
        glEnd();
    }

    glFlush();
}

void mouseClick(int button, int state, int x, int y) {
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN) {
        int openGL_X = x - (LOGIC_WIDTH / 2);
        int openGL_Y = (LOGIC_HEIGHT / 2) - y;

        mouseClicks.push_back({(double)openGL_X, (double)openGL_Y});

        glColor3f(1.0f, 0.8f, 0.0f);
        glPointSize(6.0f);
        glBegin(GL_POINTS);
            glVertex2i(openGL_X, openGL_Y);
        glEnd();
        glFlush();

        if (mouseClicks.size() == 2) {
            lines.push_back({mouseClicks[0], mouseClicks[1], false});
            mouseClicks.clear();
            glutPostRedisplay();
        }
    }
}

void keyboard(unsigned char key, int x, int y) {
    if (key == '1') {
        cout << ">> Xen hinh (Cohen-Sutherland): Giu lai phan ben TRONG khung\n";
        vector<Line> newLines;
        for (auto& line : lines) {
            Point p1_out, p2_out;
            if (cohenSutherlandClip(line.p1, line.p2, p1_out, p2_out)) {
                newLines.push_back({p1_out, p2_out, true});
            }
        }
        lines = newLines;
        glutPostRedisplay();
    } 
    else if (key == '2') {
        cout << ">> Xen hinh (Chia nhi phan): Giu lai phan ben TRONG khung\n";
        vector<Line> newLines;
        for (auto& line : lines) {
            Point p1_out, p2_out;
            if (midpointSubdivisionClip(line.p1, line.p2, p1_out, p2_out)) {
                newLines.push_back({p1_out, p2_out, true});
            }
        }
        lines = newLines;
        glutPostRedisplay();
    } 
    else if (key == 'c' || key == 'C') {
        lines.clear();
        mouseClicks.clear();
        glutPostRedisplay();
        cout << ">> Da xoa man hinh.\n";
    }
}

int main(int argc, char** argv) {
    glutInit(&argc, argv);
    glutInitDisplayMode(GLUT_SINGLE | GLUT_RGB);
    glutInitWindowSize(LOGIC_WIDTH, LOGIC_HEIGHT);
    glutInitWindowPosition(100, 100);
    glutCreateWindow("Line Clipping Fixed - Cohen Sutherland & Midpoint");

    init();

    cout << "==================================================" << endl;
    cout << "1. Click chuot 2 lan de ve doan thang AB" << endl;
    cout << "2. Nhan '1': Xen Cohen-Sutherland (Giu phan TRONG khung)" << endl;
    cout << "3. Nhan '2': Xen Chia nhi phan (Giu phan TRONG khung)" << endl;
    cout << "4. Nhan 'c': Xoa man hinh" << endl;
    cout << "==================================================" << endl;

    glutDisplayFunc(display);
    glutMouseFunc(mouseClick);
    glutKeyboardFunc(keyboard);

    glutMainLoop();
    return 0;
}